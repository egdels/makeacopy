/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.crop;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Magnifier;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.ml.corners.DetectionResult;
import de.schliweb.makeacopy.utils.image.CoordinateTransformUtils;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.Getter;
import lombok.Setter;
import org.opencv.core.Point;

/**
 * Custom view for selecting a trapezoid area on an image Allows the user to drag the corners of the
 * trapezoid to adjust the selection
 */
@SuppressWarnings({
  "NonAtomicVolatileUpdate", // requestedInitSeq is only incremented on UI thread
  "NarrowCalculation", // integer division for pixel coordinates is intentional
  "ThreadPriorityCheck" // slightly lower priority for background corner detection is intentional
})
public class TrapezoidSelectionView extends View {
  private static final String TAG = "TrapezoidSelectionView";
  private static final int CORNER_RADIUS =
      35; // Increased radius of the corner handles for better visibility
  private static final int CORNER_TOUCH_RADIUS = 70; // Increased touch area for easier interaction
  private static final int EDGE_HANDLE_RADIUS =
      18; // Smaller midpoint handle, signalling that edges (lines) can be dragged too
  private static final long ANIMATION_DURATION = 300; // Animation duration in milliseconds

  // When true, the next setImageBitmap() call will skip re-initializing corners.
  // Used when we rotate the selection ourselves to keep it in sync with image rotation.
  private boolean suppressInitOnce = false;

  /**
   * Hint that the current image is likely already cropped (e.g. loaded via gallery import or shared
   * from another app), so the {@link #looksAlreadyCropped} fallback heuristic should be applied.
   * Default {@code false}: live camera captures must never trigger the heuristic.
   *
   * <p>Set by the host fragment via {@link #setPreCroppedHint(boolean)} <em>before</em> calling
   * {@link #setImageBitmap(Bitmap)} for a newly loaded image.
   */
  private boolean preCroppedHint = false;

  /**
   * Enable or disable the "already-cropped" fallback heuristic for the next/current image. Must be
   * {@code false} for live camera captures and {@code true} only for imported/shared images.
   */
  public void setPreCroppedHint(boolean enabled) {
    this.preCroppedHint = enabled;
  }

  private Paint trapezoidPaint; // Paint for the trapezoid lines
  private Paint cornerPaint; // Paint for the corner handles
  private Paint activePaint; // Paint for the active corner handle
  private Paint backgroundPaint; // Paint for the semi-transparent background
  private Paint hintPaint; // Paint for the hint text
  private Paint hintBackgroundPaint; // Paint for the hint text background
  private Paint edgeHandlePaint; // Paint for the edge midpoint handles
  private Paint activeEdgePaint; // Paint for highlighting the actively dragged edge
  private final Path drawPath = new Path(); // Preallocated path for onDraw
  private final Paint crosshairPaint =
      new Paint(Paint.ANTI_ALIAS_FLAG); // Preallocated crosshair paint
  private final Paint indexTextPaint =
      new Paint(Paint.ANTI_ALIAS_FLAG); // Preallocated index text paint
  private PointF[] corners; // The four corners of the trapezoid
  private PointF[] animationStartCorners; // Starting positions for corner animation
  private PointF[] animationEndCorners; // Target positions for corner animation
  private float animationProgress = 1.0f; // Animation progress (0.0 to 1.0)
  private long animationStartTime; // Start time of the animation
  private boolean isAnimating = false; // Flag to track if animation is in progress

  private float[][]
      relativeCorners; // Corners as percentages of view dimensions [i][0]=x%, [i][1]=y%
  private int activeCornerIndex = -1; // Index of the currently active (touched) corner

  // === Edge dragging (parallel translation; see docs/edge_drag_pan_zoom_concept.md) ===
  private static final float EDGE_TOUCH_RADIUS_DP = 24f;

  /** Index of the edge currently being dragged (0..3, TL→TR=0, TR→BR=1, BR→BL=2, BL→TL=3) or -1. */
  private int activeEdgeIndex = -1;

  /** Frozen midpoint of the active edge at ACTION_DOWN. */
  private float edgeAnchorMidX, edgeAnchorMidY;

  /** Frozen outward unit normal of the active edge at ACTION_DOWN. */
  private float edgeAnchorNx, edgeAnchorNy;

  /** Frozen corner positions at ACTION_DOWN (used to derive parallel-translated positions). */
  private final float[] edgeAnchorXs = new float[4];

  private final float[] edgeAnchorYs = new float[4];

  // === Pan/Zoom view transform (Phase 2 step 1, see docs/edge_drag_pan_zoom_concept.md §4.1) ===
  /**
   * Pure-math representation of the canvas transform applied in {@link #onDraw(Canvas)}. Touch
   * coordinates are routed through {@link #mapTouchToLocal(float, float, float[])} before any
   * hit-test, so {@code corners} stay in the unscaled view frame.
   *
   * <p>While {@code isIdentity()} this is a strict no-op. The runtime canvas concat is realised
   * through {@link #viewMatrix} (mirroring the same {@code scale, tx, ty}).
   */
  private final CropViewTransform viewTransform = new CropViewTransform();

  /**
   * Android {@link Matrix} mirror of {@link #viewTransform}, used by {@link Canvas#concat(Matrix)}
   * in {@link #onDraw(Canvas)}. Kept in sync via {@link #syncViewMatrixFromTransform()}.
   */
  private final Matrix viewMatrix = new Matrix();

  /** Pre-allocated scratch for {@link #mapTouchToLocal(float, float, float[])}. */
  private final float[] touchLocalScratch = new float[2];

  // === Pan/Zoom gesture detectors (Phase 2 step 2/4) ===
  /** Snap‑back threshold: any scale below this collapses to identity on ACTION_UP. */
  private static final float SCALE_SNAP_THRESHOLD = 1.05f;

  /** Target zoom factor for double‑tap‑to‑zoom from identity. */
  private static final float DOUBLE_TAP_ZOOM = 2.5f;

  @Nullable private ScaleGestureDetector scaleDetector;
  @Nullable private GestureDetector tapDetector;

  /** True while a pinch is in progress (between {@code onScaleBegin} and {@code onScaleEnd}). */
  private boolean inPinchGesture = false;

  /**
   * True while a single-finger pan of the zoomed view is in progress. Pan is only engaged when
   * {@link CropViewTransform#getScale()} {@code > 1} and the initial DOWN did not hit a corner or
   * edge handle. See {@code docs/edge_drag_pan_zoom_concept.md} §4.2.
   */
  private boolean isPanning = false;

  /** Last raw view-coordinate X seen during an active pan; used to compute incremental deltas. */
  private float lastPanRawX = Float.NaN;

  /** Last raw view-coordinate Y seen during an active pan. */
  private float lastPanRawY = Float.NaN;

  /**
   * Minimum fraction of the view that must remain covered by the (mapped) image while panning.
   * Matches the soft-clamp from §4.2 of the concept document.
   */
  private static final float PAN_MIN_VIEW_OVERLAP = 0.10f;

  /**
   * Listener notified whenever {@link #viewTransform} changes (Pan/Zoom). The current Android
   * {@link Matrix} is supplied so that the host fragment can keep an underlying {@code ImageView}
   * in sync via {@link android.widget.ImageView#setImageMatrix(Matrix)}.
   */
  public interface OnViewTransformChangedListener {
    /**
     * Called on the UI thread after every Pan/Zoom mutation.
     *
     * @param scale current scale ≥ 1.0
     * @param tx current translation in view pixels
     * @param ty current translation in view pixels
     * @param matrix mirrored Android matrix (do not retain; copy if needed)
     */
    void onViewTransformChanged(float scale, float tx, float ty, @NonNull Matrix matrix);
  }

  @Nullable private OnViewTransformChangedListener viewTransformChangedListener;

  /**
   * Listener notified whenever the trapezoid corners change in a way relevant to UI hints
   * (specifically: whether at least one corner currently lies outside the original image rectangle
   * in image coordinates). See docs/edge_drag_pan_zoom_concept.md §5.3.
   */
  public interface OnCornersChangedListener {
    /**
     * Called after a corner mutation. Always invoked on the UI thread.
     *
     * @param anyCornerOffImage true if at least one corner is outside the original image rectangle
     *     (image coords [0,W] × [0,H]); false otherwise. Until a bitmap is known this is reported
     *     as false.
     */
    void onCornersChanged(boolean anyCornerOffImage);
  }

  @Nullable private OnCornersChangedListener cornersChangedListener;

  /** Last value reported to {@link #cornersChangedListener}; used to suppress duplicate fires. */
  private Boolean lastReportedAnyOff = null;

  /**
   * Listener notified when the user starts or stops dragging a corner or edge of the quadrilateral.
   * Hosting fragments use this to disable the system back gesture while a drag is in progress, so
   * that horizontal drags near screen edges do not accidentally pop the navigation back stack.
   */
  public interface OnDragStateChangedListener {
    /**
     * Called on the UI thread whenever drag state transitions.
     *
     * @param isDragging true if a corner or edge drag is currently active; false otherwise.
     */
    void onDragStateChanged(boolean isDragging);
  }

  @Nullable private OnDragStateChangedListener dragStateChangedListener;

  /** Last value reported to {@link #dragStateChangedListener}; suppresses duplicate fires. */
  private boolean lastReportedDragging = false;

  /**
   * Registers a listener notified whenever a corner/edge drag starts or ends. Pass {@code null} to
   * clear. The current state is reported synchronously on registration.
   */
  public void setOnDragStateChangedListener(@Nullable OnDragStateChangedListener listener) {
    this.dragStateChangedListener = listener;
    if (listener != null) {
      try {
        listener.onDragStateChanged(lastReportedDragging);
      } catch (Throwable t) {
        Log.w(TAG, "onDragStateChanged initial dispatch failed: " + t.getMessage());
      }
    }
  }

  /**
   * Map a raw view-coordinate touch point to the unscaled local coordinate frame in which {@code
   * corners} are stored.
   *
   * <p>While the view transform is identity (Phase 2 step 1 default; the {@code
   * FEATURE_CROP_PAN_ZOOM} flag has not yet been promoted to runtime gestures), this is a no-op
   * write of {@code (xView, yView)} into {@code out}. Once Pinch/Pan are wired up, the same call
   * returns {@code (xView - tx) / scale} so that hit-tests remain valid in local coordinates
   * regardless of zoom/pan.
   *
   * @param xView raw {@link MotionEvent} X coordinate.
   * @param yView raw {@link MotionEvent} Y coordinate.
   * @param out 2-element scratch array; required.
   */
  private void mapTouchToLocal(float xView, float yView, float[] out) {
    viewTransform.mapViewToLocal(xView, yView, out);
  }

  /**
   * Re-derive the Android {@link #viewMatrix} from the pure-math {@link #viewTransform} so that
   * {@link #onDraw(Canvas)} renders the same transformation as the touch mapping. Cheap; safe to
   * call after every {@code postScale} / {@code postTranslate}.
   *
   * <p>Currently unused at runtime — promoted to a callable hook for the upcoming Pinch‑Zoom /
   * implicit Pan integration in Phase 2 step 2.
   */
  private void syncViewMatrixFromTransform() {
    float s = viewTransform.getScale();
    float tx = viewTransform.getTx();
    float ty = viewTransform.getTy();
    viewMatrix.reset();
    viewMatrix.setScale(s, s);
    viewMatrix.postTranslate(tx, ty);
  }

  /**
   * Snap the view transform back to identity if the current scale is close to 1.0. Avoids leaving a
   * tiny residual zoom after a pinch‑in that nearly returns to the original size.
   */
  private void maybeSnapToIdentity() {
    if (viewTransform.isIdentity()) return;
    float s = viewTransform.getScale();
    if (s < SCALE_SNAP_THRESHOLD) {
      viewTransform.reset();
      syncViewMatrixFromTransform();
      notifyViewTransformChanged();
      invalidate();
    }
  }

  /**
   * Toggle between identity and {@code DOUBLE_TAP_ZOOM} centred on the supplied focal point. Called
   * by the double‑tap detector. No-op while {@code FEATURE_CROP_PAN_ZOOM == false}.
   */
  private void onDoubleTapZoom(float focalX, float focalY) {
    if (!de.schliweb.makeacopy.BuildConfig.FEATURE_CROP_PAN_ZOOM) return;
    if (viewTransform.isIdentity()) {
      // Zoom in: post‑scale by (DOUBLE_TAP_ZOOM / 1) around focal point.
      viewTransform.postScale(DOUBLE_TAP_ZOOM, focalX, focalY);
    } else {
      // Reset to identity.
      viewTransform.reset();
    }
    syncViewMatrixFromTransform();
    notifyViewTransformChanged();
    invalidate();
  }

  /**
   * Register a listener notified after every Pan/Zoom mutation. A synchronous callback with the
   * current state is dispatched immediately so that callers can prime any dependent rendering (e.g.
   * an {@code ImageView}'s image matrix) without waiting for the next gesture.
   */
  public void setOnViewTransformChangedListener(@Nullable OnViewTransformChangedListener l) {
    this.viewTransformChangedListener = l;
    notifyViewTransformChanged();
  }

  /** Latest published Pan/Zoom matrix snapshot (defensive copy). */
  @NonNull
  public Matrix getViewMatrix() {
    return new Matrix(viewMatrix);
  }

  /** Current Pan/Zoom scale (1.0 = identity, ≥ 1). */
  public float getViewScale() {
    return viewTransform.getScale();
  }

  /** True iff the Pan/Zoom transform is at identity (no zoom, no pan). */
  public boolean isViewTransformIdentity() {
    return viewTransform.isIdentity();
  }

  private void notifyViewTransformChanged() {
    if (viewTransformChangedListener == null) return;
    try {
      viewTransformChangedListener.onViewTransformChanged(
          viewTransform.getScale(), viewTransform.getTx(), viewTransform.getTy(), viewMatrix);
    } catch (Throwable t) {
      Log.w(TAG, "onViewTransformChanged dispatch failed: " + t.getMessage());
    }
  }

  /**
   * Announce the edge-drag start to accessibility services. Routed through the centralized {@link
   * de.schliweb.makeacopy.utils.ui.A11yUtils#announce(View, CharSequence)} helper so any
   * platform-specific deprecations stay confined to that utility.
   */
  private void announceEdgeDragForA11y() {
    try {
      de.schliweb.makeacopy.utils.ui.A11yUtils.announce(
          this, getResources().getString(R.string.crop_edge_dragged));
    } catch (Throwable ignore) {
      // String missing or accessibility not active — best-effort.
    }
  }

  private void notifyDragStateChanged(boolean dragging) {
    if (dragging == lastReportedDragging) return;
    lastReportedDragging = dragging;
    if (dragStateChangedListener != null) {
      try {
        dragStateChangedListener.onDragStateChanged(dragging);
      } catch (Throwable t) {
        Log.w(TAG, "onDragStateChanged dispatch failed: " + t.getMessage());
      }
    }
  }

  private boolean initialized = false; // Flag to track if corners have been initialized
  private int initializationAttempts = 0; // Counter for initialization attempts
  private int lastWidth = 0; // Last known width of the view
  private int lastHeight = 0; // Last known height of the view

  private Bitmap imageBitmap = null; // The image bitmap for edge detection
  // Track last bitmap dimensions to avoid redundant re-inits
  private int lastBitmapWidth = -1;
  private int lastBitmapHeight = -1;

  // ==== Async corner detection state ====
  private final ExecutorService cornerExec =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "CornerDetect");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
          });
  @Nullable private Future<?> cornerTask;
  private volatile int requestedInitSeq = 0; // debouncing/cancellation token

  // Debounce window for the auto-initialization triggered by onSizeChanged. When the crop screen
  // opens, the view typically receives several onSizeChanged events in quick succession while the
  // layout settles (system-inset/IME size jumps, e.g. 1546 → 1601 → 1546 px). Posting the
  // initialization with this delay (and cancelling pending posts via removeCallbacks before
  // re-posting) coalesces those events into a single detection run, so the accurate first result
  // is not overwritten by a redundant second pass.
  private static final long INIT_DEBOUNCE_MS = 150L;

  // Debounced initialization runnable to avoid synchronous heavy work in onSizeChanged
  private final Runnable initCornersRunnable =
      new Runnable() {
        @Override
        public void run() {
          if (getWidth() <= 0 || getHeight() <= 0) return;
          try {
            initializeCornersAsync();
          } catch (Throwable t) {
            Log.e(TAG, "initializeCorners runnable failed", t);
          }
        }
      };

  // Magnifier (precision loupe) plumbing
  @Nullable private View magnifierSourceView;
  @Nullable private Matrix overlayToSource; // inverse from imageToOverlay matrix
  @Nullable private Magnifier magnifier;
  private final boolean magnifierEnabled = true;
  private final float magnifierZoom = 2.5f; // 2.0..4.0
  private int magnifierSizePx = 0;
  private boolean isDraggingWithMagnifier = false;

  // Injected DocQuadOrtRunner (set by hosting Fragment via setter)
  @Setter @Nullable private de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner docQuadOrtRunner;

  // === Debug/diagnostics ===
  @Setter private boolean debugLogsEnabled = false; // enable verbose logs
  private boolean debugOverlayEnabled = false; // draw overlay with rects

  @Nullable
  private java.util.List<Rect> lastExclusionRects =
      null; // last applied system gesture exclusion rects

  // Public toggles for edge-glide configuration
  // === Edge-glide state & config ===
  @Getter @Setter private boolean edgeGlideEnabled = false; // default OFF; user/dev configurable
  private boolean isEdgeGlide = false; // true while pointer is gliding along left/right edge
  private boolean edgeGlidePending = false; // true while waiting for engage delay
  private long edgeGlideEligibleSinceMs = 0L; // timestamp when first became eligible to engage
  private boolean edgeGlideLockLeft =
      true; // which edge is locked when gliding (true=left, false=right)
  private float glideY = 0f; // accumulated Y while locked to edge
  private float lastRawY = Float.NaN; // last rawY for delta computation
  // Configurable epsilons/delay
  private float edgeSnapEnterEpsDp = 3f; // enter threshold in dp (soft)
  private int edgeGlideEngageDelayMs = 120; // engage delay in ms

  // Convert dp to px for entry epsilon
  private float edgeSnapEnterEpsPx() {
    return getResources().getDisplayMetrics().density * edgeSnapEnterEpsDp;
  }

  private float edgeSnapExitEpsPx() {
    return edgeSnapEnterEpsPx() * 2f;
  }

  public void setEdgeSnapEpsDp(float dp) {
    if (dp < 0f) dp = 0f;
    this.edgeSnapEnterEpsDp = dp;
  }

  public float getEdgeSnapEpsDp() {
    return this.edgeSnapEnterEpsDp;
  }

  public void setEdgeGlideEngageDelayMs(int ms) {
    if (ms < 0) ms = 0;
    this.edgeGlideEngageDelayMs = ms;
  }

  // ===== Snap-to-Right-Angle state (FR #72 companion; see docs §5) =====
  // Two snap-state bits per corner: edgeSnapActive[i] reflects the edge (i, i+1).
  // The snapped state is only modified during single-corner drag handling; visual cue and
  // haptic feedback react to transitions inactive→active.
  private final boolean[] edgeSnapActive = new boolean[4];
  // Index of the most recently snap-engaged edge (i, i+1); -1 when nothing is snapped.
  // Used by onDraw to render the subtle visual cue without re-evaluating snap state there.
  private int snapHighlightEdgeIndex = -1;

  // ===== User-adjustment/auto-init suppression state =====
  private boolean isUserAdjusting =
      false; // true while user is dragging a handle or shortly after release
  private boolean userHasEdited =
      false; // latched once user moved a corner; prevents auto-init overwriting
  private int autoInitIdleDelayMs = 400; // idle time after release before auto-init may run

  // Phase X / Spec compliance: ensure DocQuad is attempted only once per image (until a new image
  // is set).
  // This is strictly gated behind docquad_prod_enabled in the callsites.
  private boolean docQuadAutoInitConsumed = false;
  @Nullable private Runnable adjustIdleClearRunnable = null; // clears isUserAdjusting after idle

  public void setAutoInitIdleDelayMs(int ms) {
    if (ms < 0) ms = 0;
    this.autoInitIdleDelayMs = ms;
  }

  private void cancelAdjustIdle() {
    if (adjustIdleClearRunnable != null) {
      try {
        removeCallbacks(adjustIdleClearRunnable);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      adjustIdleClearRunnable = null;
    }
  }

  private void scheduleAdjustIdleClear() {
    cancelAdjustIdle();
    adjustIdleClearRunnable =
        new Runnable() {
          @Override
          public void run() {
            isUserAdjusting = false;
            if (debugLogsEnabled) Log.d(TAG, "User adjusting ended (idle)");
            adjustIdleClearRunnable = null;
          }
        };
    try {
      postDelayed(adjustIdleClearRunnable, autoInitIdleDelayMs);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  // Public toggles for debugging
  public void setDebugOverlayEnabled(boolean enabled) {
    this.debugOverlayEnabled = enabled;
    invalidate();
  }

  public TrapezoidSelectionView(Context context) {
    super(context);
    init();
  }

  public TrapezoidSelectionView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public TrapezoidSelectionView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    // Initialize the corners array
    corners = new PointF[4];
    for (int i = 0; i < 4; i++) {
      corners[i] = new PointF();
    }

    // Initialize animation corner arrays
    animationStartCorners = new PointF[4];
    animationEndCorners = new PointF[4];
    for (int i = 0; i < 4; i++) {
      animationStartCorners[i] = new PointF();
      animationEndCorners[i] = new PointF();
    }

    // Initialize the relative corners array (as percentages of view dimensions)
    relativeCorners = new float[4][2];

    // Initialize the paints with enhanced visual appearance
    trapezoidPaint = new Paint();
    trapezoidPaint.setColor(
        Color.rgb(255, 102, 0)); // Bright orange for better visibility on most backgrounds
    trapezoidPaint.setStrokeWidth(10); // Thicker line for better visibility
    trapezoidPaint.setStyle(Paint.Style.STROKE);
    trapezoidPaint.setAntiAlias(true);
    // Add shadow effect to make the outline stand out more
    trapezoidPaint.setShadowLayer(5.0f, 2.0f, 2.0f, Color.BLACK);

    cornerPaint = new Paint();
    cornerPaint.setColor(Color.rgb(255, 102, 0)); // Matching orange color
    cornerPaint.setStyle(Paint.Style.FILL);
    cornerPaint.setAntiAlias(true);
    // Add shadow effect to make corners stand out more
    cornerPaint.setShadowLayer(5.0f, 2.0f, 2.0f, Color.BLACK);

    activePaint = new Paint();
    activePaint.setColor(Color.rgb(255, 255, 0)); // Bright yellow for active corner
    activePaint.setStyle(Paint.Style.FILL);
    activePaint.setAntiAlias(true);
    // Add glow effect for active corner
    activePaint.setShadowLayer(8.0f, 0.0f, 0.0f, Color.rgb(255, 255, 100));

    // Initialize the background paint for the semi-transparent overlay
    backgroundPaint = new Paint();
    // Use a gradient overlay that's more visible but less intrusive
    backgroundPaint.setColor(
        Color.argb(60, 0, 150, 255)); // Semi-transparent blue with higher saturation
    backgroundPaint.setStyle(Paint.Style.FILL);
    backgroundPaint.setAntiAlias(true);

    // Initialize the hint text paint
    hintPaint = new Paint();
    hintPaint.setColor(Color.WHITE);
    hintPaint.setTextSize(40); // Large text size for visibility
    hintPaint.setTextAlign(Paint.Align.CENTER);
    hintPaint.setAntiAlias(true);

    // Initialize the hint background paint
    hintBackgroundPaint = new Paint();
    hintBackgroundPaint.setColor(Color.argb(180, 0, 0, 0)); // Semi-transparent black
    hintBackgroundPaint.setStyle(Paint.Style.FILL);
    hintBackgroundPaint.setAntiAlias(true);

    // Edge midpoint handle paint: same orange family as corners but distinct (hollow) so users
    // can tell handles for edges (lines) apart from corner handles at a glance.
    edgeHandlePaint = new Paint();
    edgeHandlePaint.setColor(Color.rgb(255, 102, 0));
    edgeHandlePaint.setStyle(Paint.Style.FILL);
    edgeHandlePaint.setAntiAlias(true);
    edgeHandlePaint.setShadowLayer(4.0f, 2.0f, 2.0f, Color.BLACK);

    // Active edge paint: bright yellow stroke drawn on top of the trapezoid outline while an
    // edge is being translated (parallel drag).
    activeEdgePaint = new Paint();
    activeEdgePaint.setColor(Color.rgb(255, 255, 0));
    activeEdgePaint.setStyle(Paint.Style.STROKE);
    activeEdgePaint.setAntiAlias(true);
    activeEdgePaint.setStrokeCap(Paint.Cap.ROUND);
    activeEdgePaint.setShadowLayer(6.0f, 0.0f, 0.0f, Color.rgb(255, 255, 100));

    // Initialize default magnifier size in px (approx 140dp)
    if (magnifierSizePx == 0) {
      float density = getResources().getDisplayMetrics().density;
      magnifierSizePx = (int) (140 * density + 0.5f);
    }

    // Pan/Zoom detectors (Phase 2 step 2/4). Always wired; the touch path itself gates on
    // BuildConfig.FEATURE_CROP_PAN_ZOOM, so unflagged builds remain inert.
    Context ctx = getContext();
    if (ctx != null) {
      scaleDetector =
          new ScaleGestureDetector(
              ctx,
              new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
                  inPinchGesture = true;
                  return true;
                }

                @Override
                public boolean onScale(@NonNull ScaleGestureDetector d) {
                  float factor = d.getScaleFactor();
                  if (factor <= 0f || Float.isNaN(factor) || Float.isInfinite(factor)) return true;
                  // Focal point is in raw view coordinates; postScale handles min/max clamping.
                  viewTransform.postScale(factor, d.getFocusX(), d.getFocusY());
                  syncViewMatrixFromTransform();
                  notifyViewTransformChanged();
                  invalidate();
                  return true;
                }

                @Override
                public void onScaleEnd(@NonNull ScaleGestureDetector d) {
                  inPinchGesture = false;
                  // Snap to identity if user pinched almost back.
                  maybeSnapToIdentity();
                }
              });
      tapDetector =
          new GestureDetector(
              ctx,
              new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(@NonNull MotionEvent e) {
                  // Must return true so the detector keeps receiving subsequent events (and
                  // recognises ACTION_UP → potential second tap → onDoubleTap). Returning the
                  // default `false` here would short-circuit further dispatch and silently
                  // disable double-tap detection.
                  return true;
                }

                @Override
                public boolean onDoubleTap(@NonNull MotionEvent e) {
                  // Only toggle if the tap is NOT on a corner handle. Edge / interior taps still
                  // trigger zoom; this matches the FR ‘double‑tap to zoom out of stuck navigation’
                  // expectation while leaving corner adjustment unaffected.
                  // Tap coords are in raw view space — map through current viewMatrix inverse for
                  // the corner hit‑test.
                  mapTouchToLocal(e.getX(), e.getY(), touchLocalScratch);
                  int corner = findCornerIndex(touchLocalScratch[0], touchLocalScratch[1]);
                  if (corner != -1) return false;
                  Log.d(
                      TAG,
                      "onDoubleTap → onDoubleTapZoom focal=(" + e.getX() + "," + e.getY() + ")");
                  onDoubleTapZoom(e.getX(), e.getY());
                  return true;
                }
              });
    }

    Log.d(TAG, "TrapezoidSelectionView initialized with user guidance");
  }

  /**
   * Updates the system gesture exclusion regions to minimize interference from system gestures when
   * interacting with crop handles. This method is applicable for API level 29 and above.
   *
   * <p>The method defines small rectangular regions around the four corner handles of the crop
   * overlay. These regions are excluded from edge system gestures, ensuring that touch interactions
   * in these areas are not intercepted by system edge gestures.
   *
   * <p>If the dimensions of the view are not valid or corners are not defined, the system gesture
   * exclusion regions are cleared.
   *
   * <p>Any exceptions thrown during the execution of this method are silently ignored to prevent
   * unintended crashes or disruptions.
   */
  private void updateSystemGestureExclusion() {
    try {
      int w = getWidth();
      int h = getHeight();
      if (w <= 0 || h <= 0) {
        setSystemGestureExclusionRects(java.util.Collections.emptyList());
        return;
      }

      float density = getResources().getDisplayMetrics().density;
      int pad = (int) (24f * density + 0.5f); // default handle padding
      int activePad = (int) (40f * density + 0.5f); // larger while dragging

      // On newer Android (SDK>=34, Android 14/15), the back-edge gesture is wider/more aggressive
      // on Pixels.
      // Use broader exclusion strips there; keep legacy widths on older SDKs to avoid
      // over-excluding.
      final boolean modernBackGesture = (Build.VERSION.SDK_INT >= 34);
      // Always-on small side strips to reduce chance of edge gestures eating DOWN near edges
      int alwaysOnSideStrip = (int) ((modernBackGesture ? 40f : 24f) * density + 0.5f);
      // Larger strips while actively dragging
      int draggingSideStrip = (int) ((modernBackGesture ? 112f : 72f) * density + 0.5f);

      java.util.ArrayList<android.graphics.Rect> rects = new java.util.ArrayList<>();

      // While actively dragging (corner or edge), aggressively exclude the left/right side
      // strips from the system back-gesture. Android limits gesture exclusion to 200dp per edge
      // (vertical extent on left/right), so we cannot cover the full view height; instead we
      // place two 200dp tall strips per side, anchored around the active drag point so the
      // user's current finger position is always inside an exclusion zone.
      // Treat implicit Pan as a "dragging" state for the purpose of back-gesture exclusion: the
      // user is performing a single-finger drag across the view and must not be interrupted by
      // the system's edge-back gesture (concept §6, pan-mode rule).
      final boolean dragging = (activeCornerIndex != -1) || (activeEdgeIndex != -1) || isPanning;
      if (dragging) {
        // 200dp per edge is the system cap; use full width strips on both sides.
        int sideStrip = (int) (200f * density + 0.5f); // very wide horizontal coverage
        int vertExtent = (int) (200f * density + 0.5f); // vertical cap per edge

        // Anchor the vertical center near the active drag point if known, otherwise view center.
        float anchorY = h * 0.5f;
        if (activeCornerIndex != -1 && corners != null) {
          anchorY = corners[activeCornerIndex].y;
        } else if (activeEdgeIndex != -1 && corners != null) {
          int a = activeEdgeIndex;
          int b = (activeEdgeIndex + 1) % 4;
          anchorY = 0.5f * (corners[a].y + corners[b].y);
        }
        int top = Math.max(0, Math.round(anchorY - vertExtent * 0.5f));
        int bottom = Math.min(h, top + vertExtent);
        if (bottom - top < vertExtent) {
          top = Math.max(0, bottom - vertExtent);
        }

        // Left strip
        rects.add(new android.graphics.Rect(0, top, Math.min(w, sideStrip), bottom));
        // Right strip
        rects.add(new android.graphics.Rect(Math.max(0, w - sideStrip), top, w, bottom));
        setSystemGestureExclusionRects(rects);
        lastExclusionRects = new java.util.ArrayList<>(rects);
        if (debugLogsEnabled) {
          Log.d(
              TAG,
              "updateSystemGestureExclusion (dragging) side-strips rects="
                  + rects.size()
                  + " anchorY="
                  + anchorY);
        }
        return;
      }

      // Exclude around handles (bigger for active one while dragging)
      if (corners != null) {
        for (int i = 0; i < 4; i++) {
          float cx = corners[i].x;
          float cy = corners[i].y;
          int p = (i == activeCornerIndex && activeCornerIndex != -1) ? activePad : pad;
          int left = Math.max(0, Math.round(cx - p));
          int top = Math.max(0, Math.round(cy - p));
          int right = Math.min(w, Math.round(cx + p));
          int bottom = Math.min(h, Math.round(cy + p));
          if (right > left && bottom > top) {
            rects.add(new android.graphics.Rect(left, top, right, bottom));
          }
        }
      }

      // Always-on thin strips at the extreme left/right of the view to protect initial touches
      rects.add(new android.graphics.Rect(0, 0, Math.min(w, alwaysOnSideStrip), h));
      rects.add(new android.graphics.Rect(Math.max(0, w - alwaysOnSideStrip), 0, w, h));

      // Additionally, while dragging, exclude thin strips along the displayed image edges
      if (activeCornerIndex != -1) {
        android.graphics.RectF img = getDisplayedImageRectF(w, h);
        if (img != null) {
          int strip = (int) (16f * density + 0.5f);
          // Top
          rects.add(
              new android.graphics.Rect(
                  Math.max(0, Math.round(img.left)),
                  Math.max(0, Math.round(img.top - strip)),
                  Math.min(w, Math.round(img.right)),
                  Math.max(0, Math.round(img.top + strip))));
          // Bottom
          rects.add(
              new android.graphics.Rect(
                  Math.max(0, Math.round(img.left)),
                  Math.min(h, Math.round(img.bottom - strip)),
                  Math.min(w, Math.round(img.right)),
                  Math.min(h, Math.round(img.bottom + strip))));
          // Left (image edge)
          rects.add(
              new android.graphics.Rect(
                  Math.max(0, Math.round(img.left - strip)),
                  Math.max(0, Math.round(img.top)),
                  Math.max(0, Math.round(img.left + strip)),
                  Math.min(h, Math.round(img.bottom))));
          // Right (image edge)
          rects.add(
              new android.graphics.Rect(
                  Math.min(w, Math.round(img.right - strip)),
                  Math.max(0, Math.round(img.top)),
                  Math.min(w, Math.round(img.right + strip)),
                  Math.min(h, Math.round(img.bottom))));
        }

        // Also exclude along the view's extreme left/right edges to counter system back gestures
        // (wider while dragging)
        rects.add(new android.graphics.Rect(0, 0, Math.min(w, draggingSideStrip), h));
        rects.add(new android.graphics.Rect(Math.max(0, w - draggingSideStrip), 0, w, h));
      }

      setSystemGestureExclusionRects(rects);
      // Keep a copy for debug overlay and optional logging
      lastExclusionRects = new java.util.ArrayList<>(rects);
      if (debugLogsEnabled) {
        Log.d(TAG, "updateSystemGestureExclusion applied rects=" + rects.size());
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Converts absolute coordinates to relative coordinates (percentages of view dimensions)
   *
   * @param x X coordinate in pixels
   * @param y Y coordinate in pixels
   * @param width View width
   * @param height View height
   * @return Array with relative coordinates [x%, y%]
   */
  private float[] absoluteToRelative(float x, float y, int width, int height) {
    if (width <= 0 || height <= 0) {
      return new float[] {0, 0};
    }
    return new float[] {x / width, y / height};
  }

  /**
   * Converts relative coordinates to absolute coordinates (pixels)
   *
   * @param relX X coordinate as percentage of width (0.0-1.0)
   * @param relY Y coordinate as percentage of height (0.0-1.0)
   * @param width View width
   * @param height View height
   * @return PointF with absolute coordinates in pixels
   */
  private PointF relativeToAbsolute(float relX, float relY, int width, int height) {
    return new PointF(relX * width, relY * height);
  }

  /**
   * Clamp a point to the displayed image bounds using FIT_CENTER logic. If no bitmap is set, clamps
   * to the view bounds. Returns a float[2] array with clamped x,y in view pixels.
   */
  private float[] clampToImageBounds(float x, float y, int viewWidth, int viewHeight) {
    if (viewWidth <= 0 || viewHeight <= 0) {
      return new float[] {x, y};
    }
    if (imageBitmap == null || imageBitmap.isRecycled()) {
      float cx = Math.max(0f, Math.min(x, viewWidth));
      float cy = Math.max(0f, Math.min(y, viewHeight));
      return new float[] {cx, cy};
    }
    int bw = imageBitmap.getWidth();
    int bh = imageBitmap.getHeight();
    if (bw <= 0 || bh <= 0) {
      float cx = Math.max(0f, Math.min(x, viewWidth));
      float cy = Math.max(0f, Math.min(y, viewHeight));
      return new float[] {cx, cy};
    }
    float bitmapAspect = (float) bw / (float) bh;
    float viewAspect = (float) viewWidth / (float) viewHeight;
    float scale;
    float offsetX = 0f;
    float offsetY = 0f;
    if (bitmapAspect > viewAspect) {
      // letterboxed: horizontal fit
      scale = (float) viewWidth / (float) bw;
      offsetY = (viewHeight - (bh * scale)) / 2f;
    } else {
      // pillarboxed: vertical fit
      scale = (float) viewHeight / (float) bh;
      offsetX = (viewWidth - (bw * scale)) / 2f;
    }
    float left = offsetX;
    float top = offsetY;
    float right = offsetX + bw * scale;
    float bottom = offsetY + bh * scale;
    // Optionally keep a 1px inset to keep handle fully visible
    // float inset = 0f;
    float cx = Math.max(left, Math.min(x, right));
    float cy = Math.max(top, Math.min(y, bottom));
    return new float[] {cx, cy};
  }

  /**
   * Returns the displayed image rect in view coordinates using FIT_CENTER logic. If there is no
   * bitmap, returns the full view rect.
   */
  @Nullable
  private android.graphics.RectF getDisplayedImageRectF(int viewWidth, int viewHeight) {
    if (viewWidth <= 0 || viewHeight <= 0) return null;
    if (imageBitmap == null || imageBitmap.isRecycled()) {
      return new android.graphics.RectF(0, 0, viewWidth, viewHeight);
    }
    int bw = imageBitmap.getWidth();
    int bh = imageBitmap.getHeight();
    if (bw <= 0 || bh <= 0) {
      return new android.graphics.RectF(0, 0, viewWidth, viewHeight);
    }
    float bitmapAspect = (float) bw / (float) bh;
    float viewAspect = (float) viewWidth / (float) viewHeight;
    float scale;
    float offsetX = 0f;
    float offsetY = 0f;
    if (bitmapAspect > viewAspect) {
      scale = (float) viewWidth / (float) bw;
      offsetY = (viewHeight - (bh * scale)) / 2f;
    } else {
      scale = (float) viewHeight / (float) bh;
      offsetX = (viewWidth - (bw * scale)) / 2f;
    }
    float left = offsetX;
    float top = offsetY;
    float right = offsetX + bw * scale;
    float bottom = offsetY + bh * scale;
    return new android.graphics.RectF(left, top, right, bottom);
  }

  /**
   * Registers (or clears, when {@code listener} is {@code null}) a listener that is notified
   * whenever a corner mutation may have changed the off-image state of the quadrilateral.
   *
   * <p>When a non-null listener is registered, an initial callback is posted asynchronously (after
   * layout/bitmap state has settled) so callers do not need to query the state separately. {@link
   * #isAnyCornerOffImage()} can additionally be queried at any time.
   */
  public void setOnCornersChangedListener(@Nullable OnCornersChangedListener listener) {
    this.cornersChangedListener = listener;
    // Reset duplicate-suppression so the new listener (or a re-registered one) reliably
    // receives the next state, even if it matches the previously reported value.
    this.lastReportedAnyOff = null;
    if (listener != null) {
      // Synthesize an initial callback so callers do not have to query separately.
      // Posted to ensure layout/bitmap state is settled before evaluation.
      post(this::notifyCornersChanged);
    }
  }

  /**
   * Returns whether at least one of the four trapezoid corners currently lies outside the original
   * image rectangle in <em>image</em> coordinates ({@code [0,W] × [0,H]}). The tolerance band used
   * by {@link #updateCorner(int, float, float)} (soft-clamp via {@link
   * CropEdgeGeometry#IMG_OOB_TOL_DEFAULT}) is intentionally <em>not</em> applied here: this method
   * reports the user-visible "outside the image" state, not the soft-clamp limit.
   *
   * <p>Returns {@code false} when the bitmap or view dimensions are not yet known.
   */
  public boolean isAnyCornerOffImage() {
    int width = getWidth();
    int height = getHeight();
    if (width <= 0 || height <= 0) return false;
    if (imageBitmap == null || imageBitmap.isRecycled()) return false;
    android.graphics.RectF rect = getDisplayedImageRectF(width, height);
    if (rect == null) return false;
    if (corners == null) return false;
    final float eps = 0.5f; // sub-pixel tolerance to avoid flicker on the boundary
    for (int i = 0; i < 4; i++) {
      PointF p = corners[i];
      if (p == null) continue;
      if (p.x < rect.left - eps
          || p.x > rect.right + eps
          || p.y < rect.top - eps
          || p.y > rect.bottom + eps) {
        return true;
      }
    }
    return false;
  }

  /**
   * Computes the current off-image state and dispatches it to {@link #cornersChangedListener},
   * suppressing duplicate consecutive callbacks. Safe to call from non-touch paths; falls back to
   * no-op when no listener is registered or when dimensions are not yet known.
   */
  private void notifyCornersChanged() {
    if (cornersChangedListener == null) return;
    boolean anyOff = isAnyCornerOffImage();
    if (lastReportedAnyOff != null && lastReportedAnyOff == anyOff) return;
    lastReportedAnyOff = anyOff;
    try {
      cornersChangedListener.onCornersChanged(anyOff);
    } catch (Throwable t) {
      Log.w(TAG, "OnCornersChangedListener threw: " + t.getMessage());
    }
  }

  /**
   * Applies the Snap-to-Right-Angle assist for a single-corner drag step (see {@code
   * docs/fr72_edit_shape_from_export_concept.md} §5). Updates {@link #edgeSnapActive} state for the
   * two adjacent edges of {@code movingIndex}, fires haptic + a11y feedback on engage, and returns
   * the (possibly nudged) corner position. Gated by {@code
   * FeatureFlags.isCropSnapRightAngleEnabled()}; when disabled this is a no-op pass-through.
   *
   * @param movingIndex index of the corner being dragged (0..3)
   * @param x desired (already clamped) x position
   * @param y desired (already clamped) y position
   * @return {@code [x, y]} — possibly adjusted to lie on a near-axis edge
   */
  private float[] applyRightAngleSnap(int movingIndex, float x, float y) {
    if (!de.schliweb.makeacopy.utils.infra.FeatureFlags.isCropSnapRightAngleEnabled()) {
      return new float[] {x, y};
    }
    if (movingIndex < 0 || movingIndex >= 4) return new float[] {x, y};

    final int prevEdge = (movingIndex + 3) & 3; // edge (prevEdge, movingIndex)
    final int nextEdge = movingIndex; // edge (movingIndex, movingIndex+1)
    final boolean wasPrev = edgeSnapActive[prevEdge];
    final boolean wasNext = edgeSnapActive[nextEdge];

    de.schliweb.makeacopy.ui.crop.geom.RightAngleSnap.Pt[] pts =
        new de.schliweb.makeacopy.ui.crop.geom.RightAngleSnap.Pt[4];
    for (int i = 0; i < 4; i++) {
      pts[i] = new de.schliweb.makeacopy.ui.crop.geom.RightAngleSnap.Pt(corners[i].x, corners[i].y);
    }
    de.schliweb.makeacopy.ui.crop.geom.RightAngleSnap.Result r =
        de.schliweb.makeacopy.ui.crop.geom.RightAngleSnap.evaluate(
            pts, movingIndex, x, y, wasPrev, wasNext);

    edgeSnapActive[prevEdge] = r.prevEdgeSnapped;
    edgeSnapActive[nextEdge] = r.nextEdgeSnapped;

    // Visual-cue index: prefer the edge that just engaged (transition off→on); otherwise pick
    // any active edge; otherwise -1 (no highlight).
    boolean engagedPrev = !wasPrev && r.prevEdgeSnapped;
    boolean engagedNext = !wasNext && r.nextEdgeSnapped;
    if (engagedPrev) snapHighlightEdgeIndex = prevEdge;
    else if (engagedNext) snapHighlightEdgeIndex = nextEdge;
    else if (r.prevEdgeSnapped) snapHighlightEdgeIndex = prevEdge;
    else if (r.nextEdgeSnapped) snapHighlightEdgeIndex = nextEdge;
    else snapHighlightEdgeIndex = -1;

    // Haptic + a11y on the inactive→active transition (once per engage event).
    if (engagedPrev || engagedNext) {
      try {
        performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
      } catch (Throwable ignore) {
        // Best-effort haptic feedback only.
      }
      if (de.schliweb.makeacopy.utils.infra.FeatureFlags.isA11yGuidanceEnabled()) {
        try {
          de.schliweb.makeacopy.utils.ui.A11yUtils.announce(
              this, getContext().getString(R.string.crop_snap_engaged));
        } catch (Throwable ignore) {
          // Best-effort accessibility announcement.
        }
      }
    }

    return new float[] {(float) r.x, (float) r.y};
  }

  /** Resets all snap state. Called on pointer-up / cancel to avoid stale highlights. */
  private void resetRightAngleSnapState() {
    for (int i = 0; i < 4; i++) edgeSnapActive[i] = false;
    snapHighlightEdgeIndex = -1;
  }

  /**
   * Updates both absolute and relative coordinates for a corner
   *
   * @param index Corner index (0-3)
   * @param x X coordinate in pixels
   * @param y Y coordinate in pixels
   */
  private void updateCorner(int index, float x, float y) {
    if (index < 0 || index >= 4) return;

    int width = getWidth();
    int height = getHeight();

    float cx;
    float cy;
    // Soft-clamp in image coordinates when edge-drag/off-screen-corners are
    // enabled and we have a known displayed image rect: corners may travel
    // up to IMG_OOB_TOL_DEFAULT (25 %) of the image dimension past the rect.
    // Otherwise fall back to the strict view/image-rect clamp used historically.
    android.graphics.RectF imgRect =
        (width > 0 && height > 0) ? getDisplayedImageRectF(width, height) : null;
    if (de.schliweb.makeacopy.BuildConfig.FEATURE_CROP_EDGE_DRAG && imgRect != null) {
      float imgW = imgRect.width();
      float imgH = imgRect.height();
      float relX = x - imgRect.left;
      float relY = y - imgRect.top;
      float[] soft =
          CropEdgeGeometry.clampToImageBoundsSoft(
              relX, relY, imgW, imgH, CropEdgeGeometry.IMG_OOB_TOL_DEFAULT);
      cx = soft[0] + imgRect.left;
      cy = soft[1] + imgRect.top;

      // Keep the handle's centre inside this view even when the soft image-space tolerance would
      // place it beyond the overlay bounds. Android does not deliver a fresh ACTION_DOWN outside
      // the view, so a released off-view corner could no longer be grabbed. The corner may still
      // be outside the displayed image rect (and therefore still trigger the off-image warning),
      // but it remains touchable after the user lifts the finger.
      cx = Math.max(0f, Math.min(cx, width));
      cy = Math.max(0f, Math.min(cy, height));
    } else {
      // Clamp the target position to the displayed image bounds (or view bounds if image unknown)
      float[] clamped = clampToImageBounds(x, y, width, height);
      cx = clamped[0];
      cy = clamped[1];
    }

    // Update absolute coordinates
    corners[index].set(cx, cy);

    // Update relative coordinates if dimensions are valid
    if (width > 0 && height > 0) {
      relativeCorners[index] = absoluteToRelative(cx, cy, width, height);
    }
    // Keep gesture exclusion rects in sync while corners move
    updateSystemGestureExclusion();
    // Re-evaluate off-image state and notify listener (suppresses duplicates internally).
    notifyCornersChanged();
  }

  // ========================= ASYNC INITIALIZATION =========================

  /** Kicks off async corner initialization (idempotent/debounced). */
  private void initializeCornersAsync() {
    final int width = getWidth();
    final int height = getHeight();

    // Suppress re-init while user is adjusting corners
    if (isUserAdjusting) {
      if (debugLogsEnabled)
        Log.d(TAG, "initializeCornersAsync: suppressed (user adjusting), will retry after idle");
      // coalesce via initCornersRunnable with a delay
      removeCallbacks(initCornersRunnable);
      try {
        postDelayed(initCornersRunnable, autoInitIdleDelayMs);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      return;
    }

    Log.d(
        TAG,
        "initializeCornersAsync called, dimensions: "
            + width
            + "x"
            + height
            + ", attempt: "
            + ++initializationAttempts);

    if (width == 0 || height == 0) {
      Log.w(TAG, "Cannot initialize corners, view has zero dimensions");
      removeCallbacks(initCornersRunnable);
      postDelayed(this::initializeCornersAsync, 100);
      return;
    }

    // User option: skip automatic edge detection and start with a simple default rectangle
    if (isSkipEdgeDetectionEnabled()) {
      Log.d(TAG, "initializeCornersAsync: edge detection skipped by user preference");
      if (cornerTask != null) {
        cornerTask.cancel(true);
        cornerTask = null;
      }
      ++requestedInitSeq;
      setDefaultCorners(width, height);
      lastWidth = width;
      lastHeight = height;
      initialized = true;
      invalidate();
      return;
    }

    // Cancel any running task
    if (cornerTask != null) {
      cornerTask.cancel(true);
      cornerTask = null;
    }
    final int seq = ++requestedInitSeq;
    final Bitmap bmp = imageBitmap; // capture

    cornerTask =
        cornerExec.submit(
            () -> {
              long start = android.os.SystemClock.uptimeMillis();
              try {
                Point[] resultViewCorners = null;

                if (bmp != null && OpenCVUtils.isInitialized()) {
                  // cheap pre-scale for detection
                  // Use central constant to match live preview detection resolution for consistent
                  // results
                  Bitmap work = bmp;
                  int maxEdge = OpenCVUtils.DETECTION_MAX_EDGE;
                  int bw = bmp.getWidth(), bh = bmp.getHeight();
                  float s = Math.min(1f, maxEdge / (float) Math.max(bw, bh));
                  if (s < 1f) {
                    work =
                        Bitmap.createScaledBitmap(
                            bmp, Math.round(bw * s), Math.round(bh * s), true);
                  }

                  // time budget (~600 ms)
                  final long budgetMs = 600;
                  resultViewCorners = detectCornersWithBudget(work, s, width, height, budgetMs);
                  if (work != bmp) work.recycle();
                }

                if (Thread.currentThread().isInterrupted()) return;

                if (resultViewCorners == null) {
                  // heuristic fallback
                  resultViewCorners = fallbackCorners(width, height, bmp);
                }

                final Point[] cornersFinal = resultViewCorners;
                post(
                    () -> {
                      if (seq != requestedInitSeq) return;
                      if (cornersFinal != null && cornersFinal.length == 4) {
                        for (int i = 0; i < 4; i++) {
                          updateCorner(i, (float) cornersFinal[i].x, (float) cornersFinal[i].y);
                        }
                      } else {
                        setDefaultCorners(width, height);
                      }
                      lastWidth = width;
                      lastHeight = height;
                      initialized = true;
                      invalidate();
                    });
              } catch (Throwable t) {
                Log.e(TAG, "Async corner init failed", t);
                post(
                    () -> {
                      if (seq != requestedInitSeq) return;
                      setDefaultCorners(width, height);
                      lastWidth = width;
                      lastHeight = height;
                      initialized = true;
                      invalidate();
                    });
              } finally {
                long dur = android.os.SystemClock.uptimeMillis() - start;
                Log.d(TAG, "initializeCornersAsync finished in " + dur + "ms");
              }
            });
  }

  /** Detect corners under a time budget; returns view-space points or null. */
  @Nullable
  private Point[] detectCornersWithBudget(
      Bitmap work, float scaleToOrig, int viewW, int viewH, long budgetMs) {
    long t0 = android.os.SystemClock.uptimeMillis();
    try {
      // Use the central policy factory (DocQuad with OpenCV fallback).
      // DocQuad is used only once per image until a new image is set.
      org.opencv.core.Point[] imgCorners;
      de.schliweb.makeacopy.ml.corners.Source detectionSource = null;
      boolean imgCornersAlreadyOriginalScale = false;
      if (!userHasEdited && !docQuadAutoInitConsumed) {
        if (BuildConfig.FEATURE_DOCQUAD_CORNERS && BuildConfig.FEATURE_BEST_OF_CROP_CORNERS) {
          imgCorners = detectBestCropCorners(work, scaleToOrig);
          if (imgCorners == null || imgCorners.length != 4) return null;
          imgCornersAlreadyOriginalScale = true;
        } else {
          de.schliweb.makeacopy.ml.corners.CornerDetector detector =
              de.schliweb.makeacopy.ml.corners.CornerDetectorFactory.forCrop(
                  getContext(), docQuadOrtRunner);

          DetectionResult r = detector.detect(work, getContext());
          if (r == null
              || !r.success
              || r.cornersOriginalTLTRBRBL == null
              || r.cornersOriginalTLTRBRBL.length != 4) {
            return null;
          }
          detectionSource = r.source;
          imgCorners = pointsFromDetectionResult(r);
        }
        // Even if the result came from OpenCV fallback inside the composite, we still consider the
        // attempt consumed.
        docQuadAutoInitConsumed = true;
      } else if (!userHasEdited
          && BuildConfig.FEATURE_DOCQUAD_CORNERS
          && BuildConfig.FEATURE_BEST_OF_CROP_CORNERS) {
        // DocQuad was already consumed by a previous (auto) initialization, but the user has not
        // edited the corners yet. A redundant re-initialization can happen when the view receives
        // several onSizeChanged events while laying out (e.g. system-inset/IME size jumps). In that
        // case falling back to plain OpenCV here would overwrite the accurate DocQuad/best-of quad
        // with the inferior Hough-fallback result. Reuse the same best-of/DocQuad path so every run
        // yields the same good corners (DocQuad is cached once per image, so this stays cheap).
        imgCorners = detectBestCropCorners(work, scaleToOrig);
        if (imgCorners == null || imgCorners.length != 4) return null;
        imgCornersAlreadyOriginalScale = true;
      } else {
        // After a user edit (or when best-of/DocQuad is disabled), use OpenCV directly.
        imgCorners = OpenCVUtils.detectDocumentCorners(getContext(), work);
        if (imgCorners == null || imgCorners.length != 4) return null;
      }

      // back-project to original image scale if we downscaled
      if (!imgCornersAlreadyOriginalScale) {
        scaleImageQuadToOriginal(imgCorners, scaleToOrig);
      }

      Bitmap refBmp = imageBitmap != null ? imageBitmap : work;
      if (refBmp == null) {
        Log.w(TAG, "Detected quad cannot be validated without a reference bitmap");
        return null;
      }
      if (!isValidImageQuad(imgCorners, refBmp.getWidth(), refBmp.getHeight())) {
        if (detectionSource == de.schliweb.makeacopy.ml.corners.Source.DOCQUAD) {
          Log.w(TAG, "DocQuad quad is invalid or degenerate — trying OpenCV fallback directly");
          org.opencv.core.Point[] openCvCorners =
              OpenCVUtils.detectDocumentCorners(getContext(), work);
          if (openCvCorners != null && openCvCorners.length == 4) {
            if (scaleToOrig < 1f) {
              double inv = 1.0 / scaleToOrig;
              for (org.opencv.core.Point p : openCvCorners) {
                p.x *= inv;
                p.y *= inv;
              }
            }
            imgCorners = openCvCorners;
          }
        }
        if (!isValidImageQuad(imgCorners, refBmp.getWidth(), refBmp.getHeight())) {
          Log.w(TAG, "Detected quad is invalid or degenerate — using full-image fallback");
          imgCorners = fullImageQuad(refBmp.getWidth(), refBmp.getHeight());
        }
      }

      // Heuristic: if the detected quad already spans (nearly) the entire image but
      // the corners do not cluster near the image's true corners, the input is most
      // likely an already-cropped image (e.g. a photo shared from the gallery whose
      // entire content is the document). In that case the model/legacy detector
      // tends to return degenerate quads — fall back to using (almost) the full
      // image rectangle so the user gets a sensible default they can fine-tune.
      if (preCroppedHint
          && refBmp != null
          && looksAlreadyCropped(imgCorners, refBmp.getWidth(), refBmp.getHeight())) {
        Log.i(
            TAG,
            "Detected quad looks degenerate on a pre-cropped image — using full-image fallback");
        imgCorners = fullImageQuad(refBmp.getWidth(), refBmp.getHeight());
      }

      // Snap the detected quad onto the strongest image gradients of the full-resolution
      // bitmap. The detectors run on heavily downscaled inputs (DocQuad: 256px letterbox,
      // OpenCV: DETECTION_MAX_EDGE), so back-projected corners carry a quantization error of
      // several pixels; the refiner removes most of it and falls back to the unrefined quad
      // whenever the edge evidence is weak or the result would be implausible.
      if (BuildConfig.FEATURE_CORNER_REFINEMENT && refBmp != null) {
        imgCorners =
            de.schliweb.makeacopy.ml.corners.EdgeSnapCornerRefiner.refine(refBmp, imgCorners);
      }

      // Transform to view-space using existing helper
      Point[] viewPts =
          transformImageToViewCoordinates(imgCorners, imageBitmap != null ? imageBitmap : work);
      if (viewPts == null || viewPts.length != 4) return null;

      // Validate quickly
      if (!validateViewCoordinates(viewPts, viewW, viewH)) {
        adjustViewCoordinates(viewPts, viewW, viewH);
      }
      return viewPts;
    } catch (Throwable e) {
      Log.w(TAG, "detectCornersWithBudget: detection failed", e);
      return null;
    } finally {
      long dt = android.os.SystemClock.uptimeMillis() - t0;
      if (dt > budgetMs) Log.w(TAG, "Corner detection over budget: " + dt + "ms");
    }
  }

  @Nullable
  private org.opencv.core.Point[] detectBestCropCorners(Bitmap work, float scaleToOrig) {
    org.opencv.core.Point[] docQuadCorners = null;
    try {
      DetectionResult docQuadResult =
          new de.schliweb.makeacopy.ml.corners.DocQuadDetector(docQuadOrtRunner)
              .detect(work, getContext());
      if (docQuadResult != null && docQuadResult.success) {
        docQuadCorners = pointsFromDetectionResult(docQuadResult);
        scaleImageQuadToOriginal(docQuadCorners, scaleToOrig);
      }
    } catch (Throwable t) {
      Log.w(TAG, "DocQuad crop detection failed in best-of mode: " + t.getMessage());
    }

    org.opencv.core.Point[] openCvCorners = null;
    boolean openCvFromHough = false;
    try {
      OpenCVUtils.OpenCvCornerDetection openCvDetection =
          OpenCVUtils.detectDocumentCornersWithOpenCvMetadata(getContext(), work);
      if (openCvDetection != null && !openCvDetection.fallbackRectangle()) {
        openCvCorners = openCvDetection.corners();
        openCvFromHough = openCvDetection.fromHoughFallback();
      }
      scaleImageQuadToOriginal(openCvCorners, scaleToOrig);
    } catch (Throwable t) {
      Log.w(TAG, "OpenCV crop detection failed in best-of mode: " + t.getMessage());
    }

    Bitmap refBmp = imageBitmap != null ? imageBitmap : work;
    if (refBmp == null) return null;
    boolean docValid = isValidImageQuad(docQuadCorners, refBmp.getWidth(), refBmp.getHeight());
    boolean openCvValid = isValidImageQuad(openCvCorners, refBmp.getWidth(), refBmp.getHeight());
    if (docValid && openCvValid) {
      double docScore = scoreImageQuad(docQuadCorners, refBmp.getWidth(), refBmp.getHeight());
      double openCvScore = scoreImageQuad(openCvCorners, refBmp.getWidth(), refBmp.getHeight());
      double requiredOpenCvLead = openCvFromHough && docScore >= 0.55 ? 0.35 : 0.03;
      if (openCvFromHough && docScore >= 0.70) {
        requiredOpenCvLead = 0.50;
      }
      if (openCvScore > docScore + requiredOpenCvLead) {
        Log.i(
            TAG,
            "Best-of crop corners: using OpenCV"
                + (openCvFromHough ? " Hough" : " contour")
                + " (openCv="
                + openCvScore
                + ", doc="
                + docScore
                + ", requiredLead="
                + requiredOpenCvLead
                + ")");
        return openCvCorners;
      }
      if (docScore >= openCvScore) {
        Log.i(TAG, "Best-of crop corners: using DocQuad (" + docScore + " >= " + openCvScore + ")");
      } else {
        Log.i(
            TAG,
            "Best-of crop corners: using DocQuad within "
                + (openCvFromHough ? "Hough guard" : "tolerance")
                + " (doc="
                + docScore
                + ", openCv="
                + openCvScore
                + ", requiredLead="
                + requiredOpenCvLead
                + ")");
      }
      return docQuadCorners;
    }
    if (docValid) return docQuadCorners;
    if (openCvValid) return openCvCorners;
    return null;
  }

  @Nullable
  private static org.opencv.core.Point[] pointsFromDetectionResult(@Nullable DetectionResult r) {
    if (r == null || r.cornersOriginalTLTRBRBL == null || r.cornersOriginalTLTRBRBL.length != 4) {
      return null;
    }
    org.opencv.core.Point[] points = new org.opencv.core.Point[4];
    for (int i = 0; i < 4; i++) {
      if (r.cornersOriginalTLTRBRBL[i] == null || r.cornersOriginalTLTRBRBL[i].length < 2) {
        return null;
      }
      points[i] =
          new org.opencv.core.Point(
              r.cornersOriginalTLTRBRBL[i][0], r.cornersOriginalTLTRBRBL[i][1]);
    }
    return points;
  }

  private static void scaleImageQuadToOriginal(
      @Nullable org.opencv.core.Point[] quad, float scaleToOrig) {
    if (quad == null || scaleToOrig >= 1f) return;
    double inv = 1.0 / scaleToOrig;
    for (org.opencv.core.Point p : quad) {
      if (p != null) {
        p.x *= inv;
        p.y *= inv;
      }
    }
  }

  /**
   * Heuristic: detects whether the detected quad is degenerate on what is most likely an
   * already-cropped image. Triggers when the bounding box of the quad covers nearly the entire
   * image, but at least one corner does not cluster near its expected image corner (TL→(0,0),
   * TR→(W,0), BR→(W,H), BL→(0,H)). In that case the model/legacy detector cannot find a real
   * document inside the frame and we should fall back to the full image rectangle.
   *
   * @param quad 4 points in image space, expected order TL, TR, BR, BL
   * @param imgW image width in pixels
   * @param imgH image height in pixels
   */
  static boolean looksAlreadyCropped(org.opencv.core.Point[] quad, int imgW, int imgH) {
    if (quad == null || quad.length != 4 || imgW <= 0 || imgH <= 0) return false;
    for (org.opencv.core.Point p : quad) {
      if (p == null) return false;
    }

    // For each corner, measure how close it sits to an image edge (in pixels) and how far
    // it sits from its expected canonical corner (TL→(0,0), TR→(W,0), BR→(W,H), BL→(0,H)).
    // If several corners hug image edges (typical for the detector running on an already
    // pre-cropped image) but at least one corner sits far away from its expected canonical
    // position, the resulting quad is degenerate (e.g. one corner stuck mid-edge cutting
    // off a large portion of the image). In that case we treat the input as "already
    // cropped" and use the full image as the initial selection.
    double diag = Math.hypot(imgW, imgH);
    double edgeThr = 0.04 * Math.min(imgW, imgH); // hug-edge tolerance
    double farThr = 0.18 * diag; // far-from-canonical-corner threshold

    org.opencv.core.Point[] expected =
        new org.opencv.core.Point[] {
          new org.opencv.core.Point(0, 0),
          new org.opencv.core.Point(imgW, 0),
          new org.opencv.core.Point(imgW, imgH),
          new org.opencv.core.Point(0, imgH)
        };
    int hugEdge = 0;
    int farFromCanonical = 0;
    int nearCanonical = 0;
    for (int i = 0; i < 4; i++) {
      double x = quad[i].x;
      double y = quad[i].y;
      double edgeDist = Math.min(Math.min(x, imgW - x), Math.min(y, imgH - y));
      if (edgeDist <= edgeThr) hugEdge++;
      double cornerDist = Math.hypot(x - expected[i].x, y - expected[i].y);
      if (cornerDist <= 0.10 * diag) nearCanonical++;
      if (cornerDist >= farThr) farFromCanonical++;
    }

    // Trigger A (edge-hugging degenerate quad):
    //  - at least 2 corners hug an image edge (the detector latched onto image borders), AND
    //  - at least one corner is far from its expected canonical position (degenerate shape), AND
    //  - the quad is not a clean full-image rectangle (all 4 corners near canonical positions).
    if (hugEdge >= 2 && farFromCanonical >= 1 && nearCanonical < 4) return true;

    // Trigger D (heavily degenerate trapezoid on a pre-cropped image):
    // Independent of edge-hugging, if the detector returns a quad whose opposing sides differ
    // dramatically in length (one pair of "parallel" sides is less than half the length of the
    // other) AND the bounding box does not cover most of the image, the quad is clearly
    // degenerate (e.g. one corner collapsed onto another, leaving a pinched/triangle-like
    // shape). This pattern is typical for the detector running on an already-cropped photo
    // where it cannot find a real document outline. Fall back to the full image rectangle.
    {
      double topLen0 = Math.hypot(quad[0].x - quad[1].x, quad[0].y - quad[1].y);
      double botLen0 = Math.hypot(quad[3].x - quad[2].x, quad[3].y - quad[2].y);
      double leftLen0 = Math.hypot(quad[0].x - quad[3].x, quad[0].y - quad[3].y);
      double rightLen0 = Math.hypot(quad[1].x - quad[2].x, quad[1].y - quad[2].y);
      double parH0 =
          (Math.max(topLen0, botLen0) > 0)
              ? Math.min(topLen0, botLen0) / Math.max(topLen0, botLen0)
              : 0;
      double parV0 =
          (Math.max(leftLen0, rightLen0) > 0)
              ? Math.min(leftLen0, rightLen0) / Math.max(leftLen0, rightLen0)
              : 0;
      double minXq = Double.POSITIVE_INFINITY, minYq = Double.POSITIVE_INFINITY;
      double maxXq = Double.NEGATIVE_INFINITY, maxYq = Double.NEGATIVE_INFINITY;
      for (int i = 0; i < 4; i++) {
        if (quad[i].x < minXq) minXq = quad[i].x;
        if (quad[i].y < minYq) minYq = quad[i].y;
        if (quad[i].x > maxXq) maxXq = quad[i].x;
        if (quad[i].y > maxYq) maxYq = quad[i].y;
      }
      double cov0 =
          Math.max(0, maxXq - minXq) * Math.max(0, maxYq - minYq) / ((double) imgW * imgH);
      if (Math.min(parH0, parV0) < 0.5 && cov0 < 0.60 && nearCanonical < 4) return true;
    }

    // Trigger C (heavily skewed quad on a pre-cropped image):
    // If two or more corners sit far away from their expected canonical image corners AND at
    // least one corner still hugs an image edge, the detected shape is heavily skewed and almost
    // certainly a mis-detection on an already-cropped image (e.g. on a PDF page where the
    // detector latched onto two image edges but the other two corners drifted far away from
    // their canonical positions). The extra hug-edge requirement avoids triggering on normal
    // photos where the document is fully inside the frame and every corner is naturally far
    // from the image corners.
    if (farFromCanonical >= 2 && hugEdge >= 1 && nearCanonical < 4) return true;

    // Trigger B (interior mis-detection on an already-cropped image):
    // The detector returned a small/medium quad fully inside the image, far away from every edge.
    // On a real photo the document almost always touches or comes close to at least one image
    // edge; if every corner sits well inside the image AND the quad's bounding box covers only a
    // small/medium fraction of the image, the input is most likely already cropped to the document
    // and the detector latched onto an interior feature (text block, figure, ...). Fall back to
    // the full image rectangle so the user can fine-tune from a sensible default.
    double interiorThr = 0.08 * Math.min(imgW, imgH); // "well inside" margin
    int allInsideCount = 0;
    double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < 4; i++) {
      double x = quad[i].x;
      double y = quad[i].y;
      if (x >= interiorThr
          && x <= imgW - interiorThr
          && y >= interiorThr
          && y <= imgH - interiorThr) {
        allInsideCount++;
      }
      if (x < minX) minX = x;
      if (y < minY) minY = y;
      if (x > maxX) maxX = x;
      if (y > maxY) maxY = y;
    }
    double bboxW = Math.max(0, maxX - minX);
    double bboxH = Math.max(0, maxY - minY);
    double bboxCoverage = (bboxW * bboxH) / ((double) imgW * (double) imgH);
    if (allInsideCount != 4 || bboxCoverage >= 0.60) return false;

    // Plausibility check: a well-shaped quad whose opposing sides are roughly the same length
    // is most likely a genuine document detection (e.g. a centred document with margin), even
    // if the bounding box covers only a small fraction of the image. Skip the fallback in that
    // case so the detector result is preserved. Only flag clearly degenerate (non-rectangular)
    // quads as interior misdetections.
    double topLen = Math.hypot(quad[0].x - quad[1].x, quad[0].y - quad[1].y);
    double botLen = Math.hypot(quad[3].x - quad[2].x, quad[3].y - quad[2].y);
    double leftLen = Math.hypot(quad[0].x - quad[3].x, quad[0].y - quad[3].y);
    double rightLen = Math.hypot(quad[1].x - quad[2].x, quad[1].y - quad[2].y);
    double parRatioH =
        (Math.max(topLen, botLen) > 0) ? Math.min(topLen, botLen) / Math.max(topLen, botLen) : 0;
    double parRatioV =
        (Math.max(leftLen, rightLen) > 0)
            ? Math.min(leftLen, rightLen) / Math.max(leftLen, rightLen)
            : 0;
    // If both opposing-side ratios are close to 1 (rectangular-ish), consider the quad plausible
    // and keep the detector result.
    return !(parRatioH >= 0.85 && parRatioV >= 0.85);
  }

  static boolean isValidImageQuad(org.opencv.core.Point[] quad, int imgW, int imgH) {
    if (quad == null || quad.length != 4 || imgW <= 0 || imgH <= 0) return false;

    for (org.opencv.core.Point p : quad) {
      if (p == null || !Double.isFinite(p.x) || !Double.isFinite(p.y)) return false;
      if (p.x < 0 || p.x > imgW || p.y < 0 || p.y > imgH) return false;
    }

    double area = signedArea(quad);
    double areaRatio = Math.abs(area) / ((double) imgW * (double) imgH);
    if (areaRatio < 0.02) return false;

    double expectedSign = Math.signum(area);
    if (expectedSign == 0) return false;
    for (int i = 0; i < 4; i++) {
      org.opencv.core.Point a = quad[i];
      org.opencv.core.Point b = quad[(i + 1) % 4];
      org.opencv.core.Point c = quad[(i + 2) % 4];
      double cross = cross(a, b, c);
      if (Math.signum(cross) != expectedSign) return false;
    }

    return true;
  }

  static double scoreImageQuad(org.opencv.core.Point[] quad, int imgW, int imgH) {
    if (!isValidImageQuad(quad, imgW, imgH)) return Double.NEGATIVE_INFINITY;

    double areaRatio = Math.abs(signedArea(quad)) / ((double) imgW * (double) imgH);
    double areaScore = 1.0 - Math.min(1.0, Math.abs(areaRatio - 0.60) / 0.60);

    double topLen = distance(quad[0], quad[1]);
    double rightLen = distance(quad[1], quad[2]);
    double bottomLen = distance(quad[3], quad[2]);
    double leftLen = distance(quad[0], quad[3]);
    double horizontalBalance = ratio(topLen, bottomLen);
    double verticalBalance = ratio(leftLen, rightLen);
    double sideBalanceScore = (horizontalBalance + verticalBalance) / 2.0;

    double minAngleScore = 1.0;
    for (int i = 0; i < 4; i++) {
      double angle = angleDegrees(quad[(i + 3) % 4], quad[i], quad[(i + 1) % 4]);
      double angleScore = 1.0 - Math.min(1.0, Math.abs(angle - 90.0) / 90.0);
      minAngleScore = Math.min(minAngleScore, angleScore);
    }

    double minX = Double.POSITIVE_INFINITY;
    double minY = Double.POSITIVE_INFINITY;
    double maxX = Double.NEGATIVE_INFINITY;
    double maxY = Double.NEGATIVE_INFINITY;
    for (org.opencv.core.Point p : quad) {
      minX = Math.min(minX, p.x);
      minY = Math.min(minY, p.y);
      maxX = Math.max(maxX, p.x);
      maxY = Math.max(maxY, p.y);
    }
    double bboxCoverage = ((maxX - minX) * (maxY - minY)) / ((double) imgW * (double) imgH);
    double coverageScore = 1.0 - Math.min(1.0, Math.abs(bboxCoverage - 0.70) / 0.70);

    return 0.35 * areaScore + 0.30 * minAngleScore + 0.20 * sideBalanceScore + 0.15 * coverageScore;
  }

  private static double distance(org.opencv.core.Point a, org.opencv.core.Point b) {
    return Math.hypot(a.x - b.x, a.y - b.y);
  }

  private static double ratio(double a, double b) {
    double max = Math.max(a, b);
    return max > 0 ? Math.min(a, b) / max : 0;
  }

  private static double angleDegrees(
      org.opencv.core.Point previous, org.opencv.core.Point center, org.opencv.core.Point next) {
    double ux = previous.x - center.x;
    double uy = previous.y - center.y;
    double vx = next.x - center.x;
    double vy = next.y - center.y;
    double denom = Math.hypot(ux, uy) * Math.hypot(vx, vy);
    if (denom == 0) return 0;
    double cos = Math.max(-1.0, Math.min(1.0, (ux * vx + uy * vy) / denom));
    return Math.toDegrees(Math.acos(cos));
  }

  private static double signedArea(org.opencv.core.Point[] quad) {
    double sum = 0;
    for (int i = 0; i < 4; i++) {
      org.opencv.core.Point a = quad[i];
      org.opencv.core.Point b = quad[(i + 1) % 4];
      sum += a.x * b.y - b.x * a.y;
    }
    return sum / 2.0;
  }

  private static double cross(
      org.opencv.core.Point a, org.opencv.core.Point b, org.opencv.core.Point c) {
    return (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
  }

  /** Builds a full-image quad (TL, TR, BR, BL) in image space. */
  private static org.opencv.core.Point[] fullImageQuad(int imgW, int imgH) {
    return new org.opencv.core.Point[] {
      new org.opencv.core.Point(0, 0),
      new org.opencv.core.Point(imgW, 0),
      new org.opencv.core.Point(imgW, imgH),
      new org.opencv.core.Point(0, imgH)
    };
  }

  /** Builds a heuristic trapezoid in view space (used as fallback). */
  @SuppressWarnings("UnusedVariable") // bmp reserved for future aspect-ratio-aware fallback
  private Point[] fallbackCorners(int viewW, int viewH, @Nullable Bitmap bmp) {
    Point[] pts = new Point[4];
    pts[0] = new Point(viewW * 0.1, viewH * 0.1);
    pts[1] = new Point(viewW * 0.9, viewH * 0.1);
    pts[2] = new Point(viewW * 0.9, viewH * 0.9);
    pts[3] = new Point(viewW * 0.1, viewH * 0.9);
    return pts;
  }

  /** Returns true when the user opted to skip automatic edge detection (camera options). */
  private boolean isSkipEdgeDetectionEnabled() {
    try {
      return getContext()
          .getSharedPreferences("export_options", Context.MODE_PRIVATE)
          .getBoolean("skip_edge_detection", false);
    } catch (Throwable t) {
      return false;
    }
  }

  private void setDefaultCorners(int width, int height) {
    updateCorner(0, width * 0.1f, height * 0.1f);
    updateCorner(1, width * 0.9f, height * 0.1f);
    updateCorner(2, width * 0.9f, height * 0.9f);
    updateCorner(3, width * 0.1f, height * 0.9f);
    Log.d(TAG, "Corners set to default");
  }

  // ========================= END ASYNC =========================

  /**
   * Transforms coordinates from image space to view space with enhanced accuracy and robustness
   *
   * @param imageCoordinates Array of points in image coordinates
   * @param bitmap The image bitmap
   * @return Array of points in view coordinates
   */
  private Point[] transformImageToViewCoordinates(Point[] imageCoordinates, Bitmap bitmap) {
    if (imageCoordinates == null || bitmap == null) {
      Log.w(TAG, "Null parameters in transformImageToViewCoordinates");
      return createDefaultViewCoordinates();
    }

    if (imageCoordinates.length != 4) {
      Log.w(TAG, "Expected 4 coordinates, got " + imageCoordinates.length);
      return createDefaultViewCoordinates();
    }

    // Get the dimensions
    int viewWidth = getWidth();
    int viewHeight = getHeight();
    int bitmapWidth = bitmap.getWidth();
    int bitmapHeight = bitmap.getHeight();

    if (viewWidth <= 0 || viewHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) {
      Log.e(
          TAG,
          "Invalid dimensions for coordinate transformation: "
              + "viewWidth="
              + viewWidth
              + ", viewHeight="
              + viewHeight
              + ", bitmapWidth="
              + bitmapWidth
              + ", bitmapHeight="
              + bitmapHeight);
      return createDefaultViewCoordinates();
    }

    // Get the current orientation for logging
    int orientation = getResources().getConfiguration().orientation;
    boolean isPortrait = orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
    Log.d(TAG, "Current orientation: " + (isPortrait ? "portrait" : "landscape"));

    // Use CoordinateTransformUtils for the core transformation
    Point[] viewCoordinates =
        CoordinateTransformUtils.transformImageToViewCoordinates(
            imageCoordinates, bitmap, viewWidth, viewHeight);

    if (viewCoordinates == null) {
      Log.w(TAG, "CoordinateTransformUtils returned null, using default coordinates");
      return createDefaultViewCoordinates();
    }

    // Apply additional processing specific to TrapezoidSelectionView

    // Apply small inset to avoid edge cases
    double insetFactor = 0.01; // 1% inset
    for (int i = 0; i < viewCoordinates.length; i++) {
      double viewX = viewCoordinates[i].x;
      double viewY = viewCoordinates[i].y;

      if (viewX <= 0) viewX = viewWidth * insetFactor;
      if (viewX >= viewWidth) viewX = viewWidth * (1 - insetFactor);
      if (viewY <= 0) viewY = viewHeight * insetFactor;
      if (viewY >= viewHeight) viewY = viewHeight * (1 - insetFactor);

      viewCoordinates[i] = new Point(viewX, viewY);

      Log.d(
          TAG,
          "Processed point "
              + i
              + ": ("
              + imageCoordinates[i].x
              + ","
              + imageCoordinates[i].y
              + ") -> ("
              + viewX
              + ","
              + viewY
              + ")");
    }

    // Validate the transformed coordinates
    if (!validateViewCoordinates(viewCoordinates, viewWidth, viewHeight)) {
      Log.w(TAG, "Transformed coordinates failed validation, using adjusted coordinates");
      adjustViewCoordinates(viewCoordinates, viewWidth, viewHeight);
    }

    return viewCoordinates;
  }

  /**
   * Creates intelligent default view coordinates when transformation fails Chooses an appropriate
   * template based on view dimensions and orientation
   *
   * @return Array of 4 points forming a default trapezoid
   */
  private Point[] createDefaultViewCoordinates() {
    int width = getWidth();
    int height = getHeight();

    // Use default values if dimensions are invalid
    if (width <= 0) width = 1000;
    if (height <= 0) height = 1000;

    // Get the current orientation
    int orientation = getResources().getConfiguration().orientation;
    boolean isPortrait = orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;

    // Calculate aspect ratio to determine document type
    float aspectRatio = (float) width / height;

    // Choose template based on orientation and aspect ratio
    String templateType;
    if (isPortrait) {
      if (aspectRatio < 0.7f) {
        templateType = "RECEIPT"; // Tall and narrow (receipt)
      } else if (aspectRatio < 0.9f) {
        templateType = "PORTRAIT_DOCUMENT"; // Standard portrait document
      } else {
        templateType = "SQUARE_DOCUMENT"; // Nearly square document
      }
    } else {
      if (aspectRatio > 1.8f) {
        templateType = "WIDE_DOCUMENT"; // Very wide document (panorama)
      } else if (aspectRatio > 1.3f) {
        templateType = "LANDSCAPE_DOCUMENT"; // Standard landscape document
      } else {
        templateType = "SQUARE_DOCUMENT"; // Nearly square document
      }
    }

    Log.d(
        TAG,
        "Creating default coordinates with template: "
            + templateType
            + ", orientation: "
            + (isPortrait ? "portrait" : "landscape")
            + ", aspect ratio: "
            + aspectRatio);

    // Create a random number generator with a seed based on dimensions
    // This ensures consistent randomization for the same view size
    java.util.Random random = new java.util.Random(width * 31L + height);

    // Create the coordinates array
    Point[] defaultCoordinates = new Point[4];

    // Apply template-specific adjustments
    switch (templateType) {
      case "RECEIPT":
        // Narrow at top, wider at bottom (typical receipt shape)
        defaultCoordinates[0] = new Point(width * 0.3, height * 0.1); // Top-left
        defaultCoordinates[1] = new Point(width * 0.7, height * 0.1); // Top-right
        defaultCoordinates[2] = new Point(width * 0.8, height * 0.9); // Bottom-right
        defaultCoordinates[3] = new Point(width * 0.2, height * 0.9); // Bottom-left
        break;

      case "PORTRAIT_DOCUMENT":
        // Slightly trapezoidal portrait document
        defaultCoordinates[0] = new Point(width * 0.2, height * 0.15); // Top-left
        defaultCoordinates[1] = new Point(width * 0.8, height * 0.1); // Top-right
        defaultCoordinates[2] = new Point(width * 0.85, height * 0.9); // Bottom-right
        defaultCoordinates[3] = new Point(width * 0.15, height * 0.85); // Bottom-left
        break;

      case "LANDSCAPE_DOCUMENT":
        // Slightly trapezoidal landscape document
        defaultCoordinates[0] = new Point(width * 0.15, height * 0.2); // Top-left
        defaultCoordinates[1] = new Point(width * 0.9, height * 0.15); // Top-right
        defaultCoordinates[2] = new Point(width * 0.85, height * 0.85); // Bottom-right
        defaultCoordinates[3] = new Point(width * 0.1, height * 0.8); // Bottom-left
        break;

      case "WIDE_DOCUMENT":
        // Very wide document with perspective
        defaultCoordinates[0] = new Point(width * 0.1, height * 0.25); // Top-left
        defaultCoordinates[1] = new Point(width * 0.9, height * 0.2); // Top-right
        defaultCoordinates[2] = new Point(width * 0.95, height * 0.8); // Bottom-right
        defaultCoordinates[3] = new Point(width * 0.05, height * 0.75); // Bottom-left
        break;

      case "SQUARE_DOCUMENT":
      default:
        // Slightly trapezoidal square document
        defaultCoordinates[0] = new Point(width * 0.2, height * 0.2); // Top-left
        defaultCoordinates[1] = new Point(width * 0.8, height * 0.15); // Top-right
        defaultCoordinates[2] = new Point(width * 0.85, height * 0.85); // Bottom-right
        defaultCoordinates[3] = new Point(width * 0.15, height * 0.8); // Bottom-left
        break;
    }

    // Add slight randomization for more natural appearance (±5%)
    for (int i = 0; i < 4; i++) {
      double randomFactorX = 1.0 + (random.nextDouble() - 0.5) * 0.1; // ±5%
      double randomFactorY = 1.0 + (random.nextDouble() - 0.5) * 0.1; // ±5%

      // Apply randomization while keeping points within reasonable bounds
      double newX = defaultCoordinates[i].x * randomFactorX;
      double newY = defaultCoordinates[i].y * randomFactorY;

      // Ensure points stay within view bounds with small margin
      newX = Math.max(width * 0.05, Math.min(width * 0.95, newX));
      newY = Math.max(height * 0.05, Math.min(height * 0.95, newY));

      defaultCoordinates[i] = new Point(newX, newY);
    }

    Log.d(TAG, "Created intelligent default coordinates:");
    Log.d(TAG, "  Top-left: (" + defaultCoordinates[0].x + ", " + defaultCoordinates[0].y + ")");
    Log.d(TAG, "  Top-right: (" + defaultCoordinates[1].x + ", " + defaultCoordinates[1].y + ")");
    Log.d(
        TAG, "  Bottom-right: (" + defaultCoordinates[2].x + ", " + defaultCoordinates[2].y + ")");
    Log.d(TAG, "  Bottom-left: (" + defaultCoordinates[3].x + ", " + defaultCoordinates[3].y + ")");

    return defaultCoordinates;
  }

  /** Updates the animation progress and corner positions */
  private void updateAnimation() {
    if (!isAnimating) {
      return;
    }

    // Calculate animation progress based on elapsed time
    long currentTime = System.currentTimeMillis();
    long elapsedTime = currentTime - animationStartTime;
    animationProgress = Math.min(1.0f, (float) elapsedTime / ANIMATION_DURATION);

    // Use ease-in-out interpolation for smoother animation
    float interpolatedProgress = interpolateEaseInOut(animationProgress);

    // Interpolate between start and end positions
    for (int i = 0; i < 4; i++) {
      corners[i].x =
          animationStartCorners[i].x
              + (animationEndCorners[i].x - animationStartCorners[i].x) * interpolatedProgress;
      corners[i].y =
          animationStartCorners[i].y
              + (animationEndCorners[i].y - animationStartCorners[i].y) * interpolatedProgress;
    }

    // Check if animation is complete
    if (animationProgress >= 1.0f) {
      isAnimating = false;

      // Update relative corners after animation completes
      int width = getWidth();
      int height = getHeight();
      if (width > 0 && height > 0) {
        for (int i = 0; i < 4; i++) {
          relativeCorners[i] = absoluteToRelative(corners[i].x, corners[i].y, width, height);
        }
      }

      Log.d(TAG, "Animation completed");
    } else {
      // Continue animation in the next frame
      invalidate();
    }
  }

  /**
   * Applies an ease-in-out interpolation to create smoother animation
   *
   * @param t Linear progress (0.0 to 1.0)
   * @return Interpolated progress
   */
  private float interpolateEaseInOut(float t) {
    // Cubic ease-in-out: t^2 * (3 - 2t)
    return t * t * (3 - 2 * t);
  }

  /**
   * Validates that the view coordinates form a valid quadrilateral
   *
   * @param coordinates Array of 4 points
   * @param viewWidth Width of the view
   * @param viewHeight Height of the view
   * @return true if coordinates are valid, false otherwise
   */
  private boolean validateViewCoordinates(Point[] coordinates, int viewWidth, int viewHeight) {
    if (coordinates == null || coordinates.length != 4) {
      return false;
    }

    // Check if all points are within view bounds
    for (Point p : coordinates) {
      if (p == null
          || !Double.isFinite(p.x)
          || !Double.isFinite(p.y)
          || p.x < 0
          || p.x > viewWidth
          || p.y < 0
          || p.y > viewHeight) {
        Log.w(
            TAG,
            "Invalid point in view coordinates: "
                + (p != null ? "(" + p.x + "," + p.y + ")" : "null"));
        return false;
      }
    }

    if (!isStrictlyConvex(coordinates)) {
      Log.w(TAG, "Quadrilateral is not strictly convex in TL/TR/BR/BL order");
      return false;
    }

    // Check if the quadrilateral has reasonable area (at least 2% of view)
    double area =
        Math.abs(
            (coordinates[0].x * (coordinates[1].y - coordinates[3].y)
                    + coordinates[1].x * (coordinates[2].y - coordinates[0].y)
                    + coordinates[2].x * (coordinates[3].y - coordinates[1].y)
                    + coordinates[3].x * (coordinates[0].y - coordinates[2].y))
                / 2.0);

    double viewArea = viewWidth * viewHeight;
    double areaRatio = area / viewArea;

    if (areaRatio < 0.02) {
      Log.w(TAG, "Quadrilateral area too small: " + areaRatio + " of view area");
      return false;
    }

    return true;
  }

  private static boolean isStrictlyConvex(Point[] quad) {
    if (quad == null || quad.length != 4) return false;
    double expectedSign = 0;
    for (int i = 0; i < 4; i++) {
      Point a = quad[i];
      Point b = quad[(i + 1) % 4];
      Point c = quad[(i + 2) % 4];
      if (a == null || b == null || c == null) return false;
      double cross = cross(a, b, c);
      if (cross == 0 || !Double.isFinite(cross)) return false;
      double sign = Math.signum(cross);
      if (expectedSign == 0) {
        expectedSign = sign;
      } else if (sign != expectedSign) {
        return false;
      }
    }
    return true;
  }

  /**
   * Adjusts view coordinates to ensure they form a valid quadrilateral
   *
   * @param coordinates Array of 4 points to adjust
   * @param viewWidth Width of the view
   * @param viewHeight Height of the view
   */
  private void adjustViewCoordinates(Point[] coordinates, int viewWidth, int viewHeight) {
    if (coordinates == null || coordinates.length != 4) {
      return;
    }

    // Calculate center of the view
    double centerX = viewWidth / 2.0;
    double centerY = viewHeight / 2.0;

    // Calculate reasonable inset (15% of the smaller dimension)
    double inset = Math.min(viewWidth, viewHeight) * 0.15;

    // Adjust each point to be within bounds and form a reasonable trapezoid
    // Top-left
    coordinates[0].x = Math.max(inset, Math.min(centerX - inset, coordinates[0].x));
    coordinates[0].y = Math.max(inset, Math.min(centerY - inset, coordinates[0].y));

    // Top-right
    coordinates[1].x = Math.max(centerX + inset, Math.min(viewWidth - inset, coordinates[1].x));
    coordinates[1].y = Math.max(inset, Math.min(centerY - inset, coordinates[1].y));

    // Bottom-right
    coordinates[2].x = Math.max(centerX + inset, Math.min(viewWidth - inset, coordinates[2].x));
    coordinates[2].y = Math.max(centerY + inset, Math.min(viewHeight - inset, coordinates[2].y));

    // Bottom-left
    coordinates[3].x = Math.max(inset, Math.min(centerX - inset, coordinates[3].x));
    coordinates[3].y = Math.max(centerY + inset, Math.min(viewHeight - inset, coordinates[3].y));

    Log.d(TAG, "Adjusted coordinates to ensure valid quadrilateral");
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    // Invalidate magnifier on size/orientation change (rebuild lazily on next drag)
    if (magnifier != null) {
      try {
        magnifier.dismiss();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    magnifier = null;
    // Update system gesture exclusion rects for new size
    updateSystemGestureExclusion();

    // Get the current orientation
    int orientation = getResources().getConfiguration().orientation;
    String orientationName =
        (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
            ? "portrait"
            : "landscape";

    Log.d(
        TAG,
        "onSizeChanged: "
            + w
            + "x"
            + h
            + " (was "
            + oldw
            + "x"
            + oldh
            + "), orientation: "
            + orientationName);

    // Initialize corners when the view size is first determined
    if (!initialized) {
      Log.d(TAG, "Scheduling corners initialization via posted runnable");
      // Debounce any previous requests and post a fresh one. Use a delayed post so that the rapid
      // succession of onSizeChanged events during initial layout (inset/IME size jumps) collapses
      // into a single detection run instead of triggering multiple redundant initializations.
      removeCallbacks(initCornersRunnable);
      lastAutoInitBitmap = imageBitmap;
      postDelayed(initCornersRunnable, INIT_DEBOUNCE_MS);
    } else if ((w != oldw || h != oldh) && w > 0 && h > 0) {
      // cancel any pending detection and reschedule/guard
      boolean detectionWasRunning = false;
      if (cornerTask != null) {
        detectionWasRunning = !cornerTask.isDone();
        cornerTask.cancel(true);
        cornerTask = null;
      }
      requestedInitSeq++; // invalidate older tasks

      // Remap existing corners letterbox-aware: the displayed image rect (FIT_CENTER)
      // changes non-linearly with the view size (scale + centering offset), so a plain
      // proportional scaling relative to the view would shift the corners off the
      // document edges (e.g. after IME/inset-driven size jumps during initial layout).
      final Bitmap bmp = imageBitmap;
      boolean remapped = false;
      if (bmp != null && !bmp.isRecycled() && oldw > 0 && oldh > 0) {
        final int bw = bmp.getWidth();
        final int bh = bmp.getHeight();
        boolean allOk = bw > 0 && bh > 0;
        if (allOk) {
          Log.d(TAG, "Size changed, remapping corners via displayed image rect");
          for (int i = 0; i < 4; i++) {
            double[] p =
                CoordinateTransformUtils.remapPointBetweenFitCenterViews(
                    corners[i].x, corners[i].y, bw, bh, oldw, oldh, w, h);
            if (p == null) {
              allOk = false;
              break;
            }
            updateCorner(i, (float) p[0], (float) p[1]);
          }
          remapped = allOk;
        }
      }
      if (!remapped) {
        // Fallback: proportional scaling of existing corners (no image rect available)
        Log.d(TAG, "Size changed, scaling corners proportionally (fallback)");
        for (int i = 0; i < 4; i++) {
          PointF newPos = relativeToAbsolute(relativeCorners[i][0], relativeCorners[i][1], w, h);
          updateCorner(i, newPos.x, newPos.y);
        }
      }

      // If an in-flight detection was cancelled by this size change, reschedule it so the
      // view does not remain stuck with default/stale corners.
      if (detectionWasRunning) {
        removeCallbacks(initCornersRunnable);
        postDelayed(initCornersRunnable, INIT_DEBOUNCE_MS);
      }

      // Update last known dimensions
      lastWidth = w;
      lastHeight = h;

      // Force a redraw with the scaled corners
      invalidate();
      postInvalidate();

      // Option: bei starkem AR-Wechsel neu erkennen (billiger & stabiler)
      if (oldw > 0 && oldh > 0) {
        float oldAspect = oldw / (float) oldh;
        float newAspect = w / (float) h;
        if (Math.abs(oldAspect - newAspect) > 0.5f) {
          removeCallbacks(initCornersRunnable);
          postDelayed(initCornersRunnable, INIT_DEBOUNCE_MS);
        }
      }
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    // Cancel any pending initialization callbacks to avoid running after detach
    removeCallbacks(initCornersRunnable);
    if (cornerTask != null) {
      cornerTask.cancel(true);
      cornerTask = null;
    }
    requestedInitSeq++;
    if (magnifier != null) {
      try {
        magnifier.dismiss();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      magnifier = null;
    }
    magnifierSourceView = null;
    overlayToSource = null;
    isDraggingWithMagnifier = false;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);

    if (!initialized) {
      Log.d(
          TAG,
          "onDraw called but not initialized yet, dimensions: " + getWidth() + "x" + getHeight());
      return;
    }

    // Pan/Zoom view transform (Phase 2 step 1; see docs/edge_drag_pan_zoom_concept.md §4.1).
    // While viewTransform is identity, the save/concat/restore pair is a strict no-op for the
    // pixel output but already establishes the rendering pathway that subsequent steps
    // (Pinch‑Zoom, implicit Pan, double‑tap reset) build on.
    final boolean applyViewMatrix =
        de.schliweb.makeacopy.BuildConfig.FEATURE_CROP_PAN_ZOOM && !viewTransform.isIdentity();
    final int viewMatrixSaveCount = applyViewMatrix ? canvas.save() : -1;
    if (applyViewMatrix) {
      // Strictly confine the zoomed/panned content to this view's bounds so that the parent
      // ConstraintLayout's clipChildren="false" (needed for corner-handle overhang) does not
      // let the scaled overlay bleed outside the preview area. The clip is applied BEFORE the
      // viewMatrix concat, i.e. in view (post-transform) coordinates.
      canvas.clipRect(0, 0, getWidth(), getHeight());
      canvas.concat(viewMatrix);
    }

    // Update animation if in progress
    if (isAnimating) {
      updateAnimation();
    }

    // Reuse preallocated path for the trapezoid
    drawPath.reset();
    drawPath.moveTo(corners[0].x, corners[0].y);
    drawPath.lineTo(corners[1].x, corners[1].y);
    drawPath.lineTo(corners[2].x, corners[2].y);
    drawPath.lineTo(corners[3].x, corners[3].y);
    drawPath.close();

    // Draw the semi-transparent background inside the trapezoid
    canvas.drawPath(drawPath, backgroundPaint);

    // Draw the trapezoid outline
    canvas.drawPath(drawPath, trapezoidPaint);

    // Highlight the active edge (when an edge is being dragged) so the user gets clear visual
    // feedback that the whole line is being translated, not just a corner.
    if (activeEdgeIndex != -1) {
      int a = activeEdgeIndex;
      int b = (activeEdgeIndex + 1) % 4;
      float ax = corners[a].x;
      float ay = corners[a].y;
      float bx = corners[b].x;
      float by = corners[b].y;
      // Draw a thicker yellow line on top of the regular outline for the active edge.
      activeEdgePaint.setStrokeWidth(trapezoidPaint.getStrokeWidth() + 6f);
      canvas.drawLine(ax, ay, bx, by, activeEdgePaint);
    }

    // Subtle visual cue for an engaged Snap-to-Right-Angle edge (FR #72 companion §5.4).
    // Render a lighter, slightly thicker accent line on top of the snapped edge.
    if (snapHighlightEdgeIndex >= 0 && snapHighlightEdgeIndex < 4) {
      int a = snapHighlightEdgeIndex;
      int b = (a + 1) & 3;
      float ax = corners[a].x;
      float ay = corners[a].y;
      float bx = corners[b].x;
      float by = corners[b].y;
      int prevColor = activeEdgePaint.getColor();
      float prevW = activeEdgePaint.getStrokeWidth();
      activeEdgePaint.setColor(Color.WHITE);
      activeEdgePaint.setStrokeWidth(trapezoidPaint.getStrokeWidth() + 2f);
      canvas.drawLine(ax, ay, bx, by, activeEdgePaint);
      activeEdgePaint.setColor(prevColor);
      activeEdgePaint.setStrokeWidth(prevW);
    }

    // Draw small midpoint handles on each edge to indicate that edges (lines) can be dragged.
    for (int i = 0; i < 4; i++) {
      int j = (i + 1) % 4;
      float mx = (corners[i].x + corners[j].x) * 0.5f;
      float my = (corners[i].y + corners[j].y) * 0.5f;
      Paint mp = (i == activeEdgeIndex) ? activePaint : edgeHandlePaint;
      canvas.drawCircle(mx, my, EDGE_HANDLE_RADIUS, mp);
    }

    // Draw the corner handles
    for (int i = 0; i < 4; i++) {
      // While the magnifier is active, do not draw the active corner as a yellow filled circle.
      if (isDraggingWithMagnifier && i == activeCornerIndex) {
        // Skip drawing the active handle to avoid a yellow circle in the magnifier; the white
        // crosshair suffices.
        continue;
      }
      Paint paint = (i == activeCornerIndex) ? activePaint : cornerPaint;
      canvas.drawCircle(corners[i].x, corners[i].y, CORNER_RADIUS, paint);
    }

    // Draw a simple crosshair at active corner while dragging (pairs well with magnifier)
    if (isDraggingWithMagnifier && activeCornerIndex != -1) {
      float cx = corners[activeCornerIndex].x;
      float cy = corners[activeCornerIndex].y;
      crosshairPaint.setColor(Color.WHITE);
      crosshairPaint.setStrokeWidth(3f);
      crosshairPaint.setShadowLayer(4f, 0f, 0f, Color.BLACK);
      float len = CORNER_RADIUS + 20f;
      // Horizontal line
      canvas.drawLine(cx - len, cy, cx + len, cy, crosshairPaint);
      // Vertical line
      canvas.drawLine(cx, cy - len, cx, cy + len, crosshairPaint);
    }

    // Draw corner indices (avoid drawing the active corner's digit while magnifier is active so it
    // doesn't appear inside the loupe)
    indexTextPaint.setColor(Color.WHITE);
    indexTextPaint.setTextSize(40);
    indexTextPaint.setTextAlign(Paint.Align.CENTER);
    for (int i = 0; i < 4; i++) {
      if (isDraggingWithMagnifier && i == activeCornerIndex) {
        continue; // skip active corner digit to keep the loupe clean (only white crosshair visible)
      }
      canvas.drawText(String.valueOf(i), corners[i].x, corners[i].y + 15, indexTextPaint);
    }

    // Draw user guidance hints
    drawUserGuidance(canvas);

    // Debug overlay (image rect + exclusion rects + hit areas)
    if (debugOverlayEnabled) {
      drawDebugOverlay(canvas);
    }

    if (applyViewMatrix) {
      canvas.restoreToCount(viewMatrixSaveCount);
    }

    Log.d(TAG, "Trapezoid drawn with dimensions: " + getWidth() + "x" + getHeight());
  }

  private void drawDebugOverlay(Canvas canvas) {
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) return;

    // Draw displayed image rect
    RectF img = getDisplayedImageRectF(w, h);
    if (img != null) {
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(3f);
      p.setColor(Color.argb(220, 0, 200, 0)); // green
      canvas.drawRect(img, p);
    }

    // Draw system gesture exclusion rects
    if (lastExclusionRects != null && !lastExclusionRects.isEmpty()) {
      Paint p2 = new Paint(Paint.ANTI_ALIAS_FLAG);
      p2.setStyle(Paint.Style.STROKE);
      p2.setStrokeWidth(2f);
      p2.setColor(Color.argb(220, 220, 0, 0)); // red
      for (Rect r : lastExclusionRects) {
        canvas.drawRect(r, p2);
      }
    }

    // Draw active handle touch area
    if (activeCornerIndex != -1) {
      float cx = corners[activeCornerIndex].x;
      float cy = corners[activeCornerIndex].y;
      Paint p3 = new Paint(Paint.ANTI_ALIAS_FLAG);
      p3.setStyle(Paint.Style.STROKE);
      p3.setStrokeWidth(2f);
      p3.setColor(Color.CYAN);
      canvas.drawCircle(cx, cy, CORNER_TOUCH_RADIUS, p3);
    }

    // Draw edge-glide state text
    if (isEdgeGlide) {
      Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
      tp.setColor(Color.WHITE);
      tp.setTextSize(36f);
      tp.setShadowLayer(4f, 0f, 0f, Color.BLACK);
      canvas.drawText("[edge-glide]", 20f, h - 40f, tp);
    }
  }

  private int bottomUiInsetPx = 0;

  /**
   * Sets the bottom UI inset in pixels to keep hints clear of overlaid controls (e.g., rotation
   * bar).
   */
  public void setBottomUiInsetPx(int insetPx) {
    if (insetPx < 0) insetPx = 0;
    if (this.bottomUiInsetPx != insetPx) {
      this.bottomUiInsetPx = insetPx;
      invalidate();
    }
  }

  private static int dp(Context ctx, int dp) {
    float d = ctx.getResources().getDisplayMetrics().density;
    return (int) (dp * d + 0.5f);
  }

  private void drawUserGuidance(Canvas canvas) {
    int width = getWidth();
    int height = getHeight();

    // Don't draw hints if dimensions are invalid
    if (width <= 0 || height <= 0) {
      return;
    }

    // Determine the hint to show based on the current state
    String hint;
    float hintY;

    if (activeEdgeIndex != -1) {
      // Show edge-specific hint while a line/edge is being translated in parallel.
      switch (activeEdgeIndex) {
        case 0:
          hint = "Drag to move top edge";
          break;
        case 1:
          hint = "Drag to move right edge";
          break;
        case 2:
          hint = "Drag to move bottom edge";
          break;
        case 3:
          hint = "Drag to move left edge";
          break;
        default:
          hint = "Drag edges to adjust document selection";
          break;
      }
      // Place hint opposite to the active edge so the finger does not cover it.
      if (activeEdgeIndex == 0) { // top edge dragged → hint near bottom
        int baseBottomOffset = Math.max(100, bottomUiInsetPx + dp(getContext(), 12));
        hintY = height - baseBottomOffset;
      } else if (activeEdgeIndex == 2) { // bottom edge dragged → hint near top
        hintY = 100;
      } else { // left/right edges → bottom by default
        int baseBottomOffset = Math.max(100, bottomUiInsetPx + dp(getContext(), 12));
        hintY = height - baseBottomOffset;
      }
      drawHintText(canvas, hint, width / 2f, hintY);
    } else if (activeCornerIndex != -1) {
      // Show corner-specific hints when a corner is being dragged
      switch (activeCornerIndex) {
        case 0: // Top-left
          hint = "Drag to adjust top-left corner";
          break;
        case 1: // Top-right
          hint = "Drag to adjust top-right corner";
          break;
        case 2: // Bottom-right
          hint = "Drag to adjust bottom-right corner";
          break;
        case 3: // Bottom-left
          hint = "Drag to adjust bottom-left corner";
          break;
        default:
          hint = "Drag corners to adjust document selection";
          break;
      }

      // Determine position based on which corner is active

      // Position hint at the bottom of the screen for top corners
      // and at the top of the screen for bottom corners
      if (activeCornerIndex < 2) { // Top corners
        int baseBottomOffset = Math.max(100, bottomUiInsetPx + dp(getContext(), 12));
        hintY = height - baseBottomOffset; // Position above bottom UI
      } else { // Bottom corners
        hintY = 100; // Position near top
      }

      // Draw the hint
      drawHintText(canvas, hint, width / 2f, hintY);

    } else {
      // No corner is active, show general guidance

      // Check if the trapezoid is close to a rectangle
      boolean isNearlyRectangular = isNearlyRectangular();

      if (isNearlyRectangular) {
        hint = "Drag corners or edges to match document";
      } else {
        hint = "Drag corners or edges to fine-tune selection";
      }

      // Position the hint at the bottom of the screen, considering bottom UI inset
      int baseBottomOffset = Math.max(100, bottomUiInsetPx + dp(getContext(), 12));
      hintY = height - baseBottomOffset;

      // Draw the hint
      drawHintText(canvas, hint, width / 2, hintY);

      // Draw indicators for corners that might need adjustment
      highlightCornersNeedingAdjustment(canvas);
    }
  }

  /**
   * Draws a hint text with a background for better visibility
   *
   * @param canvas Canvas to draw on
   * @param text Text to display
   * @param x X coordinate (center of text)
   * @param y Y coordinate (baseline of text)
   */
  private void drawHintText(Canvas canvas, String text, float x, float y) {
    // Measure text dimensions
    android.graphics.Rect textBounds = new android.graphics.Rect();
    hintPaint.getTextBounds(text, 0, text.length(), textBounds);

    // Calculate background rectangle with padding
    int padding = 20;
    android.graphics.RectF bgRect =
        new android.graphics.RectF(
            x - textBounds.width() / 2 - padding,
            y - textBounds.height() - padding,
            x + textBounds.width() / 2 + padding,
            y + padding);

    // Draw rounded rectangle background
    canvas.drawRoundRect(bgRect, 15, 15, hintBackgroundPaint);

    // Draw text
    canvas.drawText(text, x, y, hintPaint);
  }

  /**
   * Highlights corners that might need adjustment based on the trapezoid shape
   *
   * @param canvas Canvas to draw on
   */
  private void highlightCornersNeedingAdjustment(Canvas canvas) {
    // This is a simplified implementation that highlights corners
    // that are too close to each other or to the edges

    int width = getWidth();
    int height = getHeight();

    // Minimum distance from edges (5% of dimension)
    float minEdgeDistance = Math.min(width, height) * 0.05f;

    // Check each corner
    for (int i = 0; i < 4; i++) {
      boolean needsAdjustment =
          corners[i].x < minEdgeDistance
              || corners[i].x > width - minEdgeDistance
              || corners[i].y < minEdgeDistance
              || corners[i].y > height - minEdgeDistance;

      // Check if too close to edges

      // If corner needs adjustment, highlight it
      if (needsAdjustment) {
        Paint highlightPaint = new Paint();
        highlightPaint.setColor(Color.YELLOW);
        highlightPaint.setStrokeWidth(3);
        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setAntiAlias(true);

        // Draw a pulsating circle
        long time = System.currentTimeMillis() % 1000;
        float pulseRadius = CORNER_RADIUS + 5 + (float) (Math.sin(time / 1000.0 * 2 * Math.PI) * 5);

        canvas.drawCircle(corners[i].x, corners[i].y, pulseRadius, highlightPaint);
      }
    }
  }

  /**
   * Checks if the trapezoid is nearly rectangular
   *
   * @return true if the trapezoid is nearly rectangular, false otherwise
   */
  private boolean isNearlyRectangular() {
    // Calculate slopes of the top and bottom edges
    double topSlope =
        Math.abs((corners[1].y - corners[0].y) / (corners[1].x - corners[0].x + 0.0001));
    double bottomSlope =
        Math.abs((corners[2].y - corners[3].y) / (corners[2].x - corners[3].x + 0.0001));

    // Calculate slopes of the left and right edges
    double leftSlope =
        Math.abs((corners[3].y - corners[0].y) / (corners[3].x - corners[0].x + 0.0001));
    double rightSlope =
        Math.abs((corners[2].y - corners[1].y) / (corners[2].x - corners[1].x + 0.0001));

    // Check if the slopes are similar (indicating a rectangle)
    return Math.abs(topSlope - bottomSlope) < 0.1 && Math.abs(leftSlope - rightSlope) < 0.1;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    // Pan/Zoom gesture detection (Phase 2 step 2/4). Pinch‑Zoom and double‑tap are wired to a
    // shared ScaleGestureDetector + GestureDetector. They short‑circuit the corner/edge drag
    // path while a multi‑touch gesture is in progress and are inert when the feature flag is
    // off. Detectors are fed in raw view coordinates (NOT mapped through viewMatrix) — they
    // operate in the same coordinate system as the canvas before our concat.
    if (de.schliweb.makeacopy.BuildConfig.FEATURE_CROP_PAN_ZOOM) {
      boolean wasPinch = inPinchGesture;
      if (scaleDetector != null) {
        scaleDetector.onTouchEvent(event);
      }
      if (tapDetector != null) {
        tapDetector.onTouchEvent(event);
      }
      // If a pinch just started, cancel any in‑flight single‑pointer drag (corner, edge or
      // pan) so the trapezoid / view does not jump when the second finger arrives. We
      // synthesise an ACTION_CANCEL by clearing the per‑drag state and notifying listeners.
      if (!wasPinch && inPinchGesture) {
        if (activeCornerIndex != -1 || activeEdgeIndex != -1 || isPanning) {
          activeCornerIndex = -1;
          activeEdgeIndex = -1;
          isPanning = false;
          lastPanRawX = Float.NaN;
          lastPanRawY = Float.NaN;
          isDraggingWithMagnifier = false;
          if (magnifier != null) {
            try {
              magnifier.dismiss();
            } catch (Throwable ignore) {
              // Best-effort
            }
          }
          isUserAdjusting = false;
          notifyDragStateChanged(false);
          updateSystemGestureExclusion();
          invalidate();
        }
      }
      // While a pinch is active, swallow further events so that ACTION_MOVE doesn't drag a
      // corner.
      if (inPinchGesture) {
        return true;
      }
    }

    // Pan/Zoom: route raw event coordinates through the view transform so that all
    // hit-tests below operate in the same unscaled local frame as `corners`. While the
    // transform is identity (Phase 2 step 1 default), this is a strict no-op.
    mapTouchToLocal(event.getX(), event.getY(), touchLocalScratch);
    float x = touchLocalScratch[0];
    float y = touchLocalScratch[1];

    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        // Prime exclusion even before we know if a handle is active
        updateSystemGestureExclusion();
        if (debugLogsEnabled) {
          Log.d(
              TAG,
              "ACTION_DOWN x="
                  + x
                  + ", y="
                  + y
                  + ", rawX="
                  + event.getRawX()
                  + ", rawY="
                  + event.getRawY());
        }
        // Check if a corner was touched
        activeCornerIndex = findCornerIndex(x, y);
        if (activeCornerIndex != -1) {
          // Mark that user has edited corners (suppresses DocQuad re-init)
          userHasEdited = true;
          // Mark that the user is adjusting and cancel any pending idle-clear
          isUserAdjusting = true;
          cancelAdjustIdle();
          // Prevent parents (e.g., ViewPager/ScrollView) from intercepting during drag
          try {
            getParent().requestDisallowInterceptTouchEvent(true);
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }

          // Edge-glide initial state
          isEdgeGlide = false;
          edgeGlidePending = false;
          edgeGlideEligibleSinceMs = 0L;
          lastRawY = event.getRawY();
          glideY = corners[activeCornerIndex].y;

          // Expand gesture exclusion while dragging
          updateSystemGestureExclusion();

          // Initialize and show magnifier if enabled and source is set.
          // Magnifier expects coordinates in the source view's coordinate space (i.e. the
          // on-screen overlay/source view). Pass the *raw* event coordinates here, NOT the
          // post-mapTouchToLocal values: under Pan/Zoom, the local frame is the unscaled
          // pre-viewMatrix space, while the source view is currently rendered with
          // viewMatrix · baseFitMatrix, so its visible content matches the raw screen position.
          ensureMagnifier();
          if (magnifier != null) {
            PointF src = toSourceCoords(event.getX(), event.getY());
            try {
              magnifier.show(src.x, src.y);
            } catch (Throwable t) {
              Log.w(TAG, "magnifier.show failed: " + t.getMessage());
            }
            isDraggingWithMagnifier = true;
          }
          notifyDragStateChanged(true);
          invalidate();
          return true;
        }

        // No corner hit → check for edge hit (parallel-translation drag).
        if (de.schliweb.makeacopy.BuildConfig.FEATURE_CROP_EDGE_DRAG) {
          int edgeIdx = findEdgeHitIndex(x, y);
          if (edgeIdx != -1) {
            activeEdgeIndex = edgeIdx;
            userHasEdited = true;
            isUserAdjusting = true;
            cancelAdjustIdle();
            try {
              getParent().requestDisallowInterceptTouchEvent(true);
            } catch (Throwable ignore) {
              // Best-effort
            }
            // Snapshot corner state and edge geometry at touch-down.
            for (int i = 0; i < 4; i++) {
              edgeAnchorXs[i] = corners[i].x;
              edgeAnchorYs[i] = corners[i].y;
            }
            int a = edgeIdx;
            int b = (edgeIdx + 1) % 4;
            edgeAnchorMidX = 0.5f * (edgeAnchorXs[a] + edgeAnchorXs[b]);
            edgeAnchorMidY = 0.5f * (edgeAnchorYs[a] + edgeAnchorYs[b]);
            float[] n = CropEdgeGeometry.outwardUnitNormal(edgeAnchorXs, edgeAnchorYs, edgeIdx);
            edgeAnchorNx = n[0];
            edgeAnchorNy = n[1];
            updateSystemGestureExclusion();
            notifyDragStateChanged(true);
            // A11y: announce edge-drag start so TalkBack users get audible feedback.
            if (de.schliweb.makeacopy.BuildConfig.FEATURE_A11Y_GUIDANCE) {
              announceEdgeDragForA11y();
            }
            invalidate();
            if (debugLogsEnabled) {
              Log.d(TAG, "ACTION_DOWN edge hit, edgeIndex=" + edgeIdx);
            }
            return true;
          }
        }

        // No corner and no edge hit. If Pan/Zoom is enabled and the view is currently zoomed,
        // start an implicit pan: subsequent ACTION_MOVE events translate the viewMatrix.
        // See docs/edge_drag_pan_zoom_concept.md §4.2.
        if (de.schliweb.makeacopy.BuildConfig.FEATURE_CROP_PAN_ZOOM
            && viewTransform.getScale() > CropViewTransform.MIN_SCALE + 1e-4f) {
          isPanning = true;
          lastPanRawX = event.getRawX();
          lastPanRawY = event.getRawY();
          try {
            getParent().requestDisallowInterceptTouchEvent(true);
          } catch (Throwable ignore) {
            // Best-effort
          }
          updateSystemGestureExclusion();
          // Report drag-state so the host fragment's OnBackPressedCallback also blocks back.
          notifyDragStateChanged(true);
          if (debugLogsEnabled) {
            Log.d(TAG, "ACTION_DOWN pan start, scale=" + viewTransform.getScale());
          }
          return true;
        }

        invalidate();
        // When Pan/Zoom is enabled we MUST return true so that subsequent ACTION_MOVE/UP and the
        // ACTION_DOWN of a second tap reach this view. Otherwise Android short-circuits the
        // gesture stream after the initial DOWN, which silently disables the double-tap and
        // pinch-zoom detectors. (Concept §4.3, §4.4.)
        if (de.schliweb.makeacopy.BuildConfig.FEATURE_CROP_PAN_ZOOM) {
          return true;
        }
        return false;

      case MotionEvent.ACTION_MOVE:
        // Move the active corner
        if (activeCornerIndex != -1) {
          // Still adjusting while dragging; ensure idle-clear is cancelled
          isUserAdjusting = true;
          cancelAdjustIdle();
          // Keep parents from intercepting during drag and keep gesture exclusion updated
          try {
            getParent().requestDisallowInterceptTouchEvent(true);
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
          updateSystemGestureExclusion();

          // Edge-glide handling along left/right image edges
          RectF img = getDisplayedImageRectF(getWidth(), getHeight());
          float tx = x;
          float ty = y;
          if (img != null) {
            // Soft Edge-Glide: only engage when the pointer is actually OUTSIDE the image rect
            // by more than the enter epsilon. While inside, never force-lock X to the edge.
            if (edgeGlideEnabled) {
              float enterEps = edgeSnapEnterEpsPx();
              float exitEps = edgeSnapExitEpsPx();

              boolean outsideLeft = x < (img.left - enterEps);
              boolean outsideRight = x > (img.right + enterEps);
              boolean insideWithMargin = x >= (img.left + exitEps) && x <= (img.right - exitEps);

              long now = android.os.SystemClock.uptimeMillis();

              if (isEdgeGlide) {
                // While gliding, keep X locked to the chosen edge and accumulate Y by rawY deltas
                float lockX = edgeGlideLockLeft ? img.left : img.right;
                if (!Float.isNaN(lastRawY)) {
                  float dy = event.getRawY() - lastRawY;
                  glideY += dy;
                }
                lastRawY = event.getRawY();
                tx = lockX;
                ty = glideY;

                // Exit gliding when clearly back inside (beyond exit hysteresis)
                if (insideWithMargin) {
                  isEdgeGlide = false;
                  edgeGlidePending = false;
                  edgeGlideEligibleSinceMs = 0L;
                  if (debugLogsEnabled) Log.d(TAG, "Exit edge-glide (back inside), x=" + x);
                }
              } else {
                // Not yet gliding. Consider eligibility only if really outside.
                boolean eligible = outsideLeft || outsideRight;
                if (eligible) {
                  // Start or continue pending timer
                  if (!edgeGlidePending) {
                    edgeGlidePending = true;
                    edgeGlideEligibleSinceMs = now;
                    edgeGlideLockLeft = outsideLeft; // remember which edge we will lock to
                    if (debugLogsEnabled)
                      Log.d(
                          TAG,
                          "Edge-glide eligible ("
                              + (outsideLeft ? "left" : "right")
                              + ") starting delay at x="
                              + x);
                  }
                  // Engage after delay
                  if (edgeGlidePending
                      && (now - edgeGlideEligibleSinceMs) >= edgeGlideEngageDelayMs) {
                    isEdgeGlide = true;
                    edgeGlidePending = false;
                    glideY = corners[activeCornerIndex].y; // start from current handle y
                    lastRawY = event.getRawY();
                    if (debugLogsEnabled)
                      Log.d(
                          TAG,
                          "Enter edge-glide after delay ("
                              + (edgeGlideLockLeft ? "left" : "right")
                              + ") at x="
                              + x);
                    // Lock immediately on engage
                    float lockX = edgeGlideLockLeft ? img.left : img.right;
                    tx = lockX;
                    ty = glideY;
                  }
                } else {
                  // No longer eligible -> cancel pending
                  if (edgeGlidePending) {
                    edgeGlidePending = false;
                    edgeGlideEligibleSinceMs = 0L;
                    if (debugLogsEnabled)
                      Log.d(TAG, "Cancel edge-glide pending (pointer not outside), x=" + x);
                  }
                }
              }
            }
          }

          // Snap-to-Right-Angle: nudge near-axis adjacent edges onto 0°/90° (FR #72 companion).
          float[] snapped = applyRightAngleSnap(activeCornerIndex, tx, ty);
          tx = snapped[0];
          ty = snapped[1];

          // Use updateCorner to maintain both absolute and relative coordinates
          updateCorner(activeCornerIndex, tx, ty);

          // Update magnifier position if active. Use raw event coordinates so the magnifier
          // shows the on-screen content under the finger; under Pan/Zoom this is what the
          // source view actually renders (viewMatrix · baseFitMatrix), whereas tx/ty are in
          // the unscaled pre-viewMatrix local frame and would yield the wrong source area.
          if (isDraggingWithMagnifier && magnifier != null) {
            PointF src = toSourceCoords(event.getX(), event.getY());
            try {
              magnifier.show(src.x, src.y);
            } catch (Throwable t) {
              Log.w(TAG, "magnifier.show(move) failed: " + t.getMessage());
            }
          }

          // Log the updated corner position
          if (debugLogsEnabled) {
            Log.d(
                TAG,
                "MOVE activeCorner="
                    + activeCornerIndex
                    + ", x/y=("
                    + x
                    + ","
                    + y
                    + "), tx/ty=("
                    + tx
                    + ","
                    + ty
                    + ")"
                    + ", rel=("
                    + relativeCorners[activeCornerIndex][0]
                    + ","
                    + relativeCorners[activeCornerIndex][1]
                    + ")"
                    + (isEdgeGlide ? " [edge-glide]" : ""));
          }

          invalidate();
          return true;
        }

        // Edge drag (parallel translation): apply only the orthogonal
        // component of the finger motion to both endpoints of the active edge.
        if (activeEdgeIndex != -1) {
          isUserAdjusting = true;
          cancelAdjustIdle();
          try {
            getParent().requestDisallowInterceptTouchEvent(true);
          } catch (Throwable ignore) {
            // Best-effort
          }
          updateSystemGestureExclusion();

          CropEdgeGeometry.EdgeTranslation res =
              CropEdgeGeometry.applyEdgeTranslation(
                  edgeAnchorXs,
                  edgeAnchorYs,
                  activeEdgeIndex,
                  edgeAnchorMidX,
                  edgeAnchorMidY,
                  edgeAnchorNx,
                  edgeAnchorNy,
                  x,
                  y);
          if (res.applied) {
            // Update both endpoints via updateCorner so absolute, relative, and
            // gesture-exclusion state stay in sync. updateCorner soft-clamps to
            // image bounds (with off-screen tolerance) when feasible.
            int a = activeEdgeIndex;
            int b = (activeEdgeIndex + 1) % 4;
            updateCorner(a, res.xs[a], res.ys[a]);
            updateCorner(b, res.xs[b], res.ys[b]);
          }
          if (debugLogsEnabled) {
            Log.d(
                TAG,
                "MOVE edge="
                    + activeEdgeIndex
                    + ", applied="
                    + res.applied
                    + ", dxOrth="
                    + res.dxOrth
                    + ", dyOrth="
                    + res.dyOrth);
          }
          invalidate();
          return true;
        }
        // Implicit Pan (Phase 2 step 3): translate viewMatrix by raw delta while panning.
        // Translation is clamped so that the mapped image rect still overlaps the view by
        // at least PAN_MIN_VIEW_OVERLAP in each axis (see §4.2 of the concept doc).
        if (de.schliweb.makeacopy.BuildConfig.FEATURE_CROP_PAN_ZOOM && isPanning) {
          float rawX = event.getRawX();
          float rawY = event.getRawY();
          if (!Float.isNaN(lastPanRawX) && !Float.isNaN(lastPanRawY)) {
            float dx = rawX - lastPanRawX;
            float dy = rawY - lastPanRawY;
            if (dx != 0f || dy != 0f) {
              viewTransform.postTranslate(dx, dy);
              // Clamp against the displayed image rect (in local coords) for the soft 10 %
              // overlap rule. We use the bitmap rect as displayed under fitCenter — same
              // domain that overlayToSource is built on.
              RectF img = getDisplayedImageRectF(getWidth(), getHeight());
              if (img != null) {
                viewTransform.clampTranslateToView(
                    img.left,
                    img.top,
                    img.right,
                    img.bottom,
                    getWidth(),
                    getHeight(),
                    PAN_MIN_VIEW_OVERLAP);
              }
              syncViewMatrixFromTransform();
              notifyViewTransformChanged();
              invalidate();
            }
          }
          lastPanRawX = rawX;
          lastPanRawY = rawY;
          return true;
        }
        break;

      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (debugLogsEnabled) {
          Log.d(
              TAG,
              (event.getAction() == MotionEvent.ACTION_UP ? "ACTION_UP" : "ACTION_CANCEL")
                  + " activeCorner="
                  + activeCornerIndex
                  + ", x="
                  + x
                  + ", y="
                  + y
                  + ", rawX="
                  + event.getRawX()
                  + ", rawY="
                  + event.getRawY());
        }
        // Release the active corner
        if (activeCornerIndex != -1) {
          // Ensure relative coordinates are updated when touch ends
          updateCorner(
              activeCornerIndex, corners[activeCornerIndex].x, corners[activeCornerIndex].y);
          Log.d(
              TAG,
              "Touch released, final corner "
                  + activeCornerIndex
                  + " position: "
                  + "("
                  + corners[activeCornerIndex].x
                  + ","
                  + corners[activeCornerIndex].y
                  + ")");
        }
        // Dismiss magnifier if shown
        if (magnifier != null && isDraggingWithMagnifier) {
          try {
            magnifier.dismiss();
          } catch (Throwable t) {
            Log.w(TAG, "magnifier.dismiss failed: " + t.getMessage());
          }
        }
        // Allow parents to intercept again and shrink gesture exclusion back to normal
        try {
          getParent().requestDisallowInterceptTouchEvent(false);
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
        isDraggingWithMagnifier = false;
        activeCornerIndex = -1;
        // Reset edge-drag state
        activeEdgeIndex = -1;
        // Reset implicit Pan state and snap residual zoom on lift.
        if (isPanning) {
          isPanning = false;
          lastPanRawX = Float.NaN;
          lastPanRawY = Float.NaN;
          // After a pan ends, scale itself didn't change, but if the user happened to
          // pinch-out and then released without crossing the snap threshold, this catches
          // the residual case.
          maybeSnapToIdentity();
        }
        notifyDragStateChanged(false);
        // Reset edge-glide state
        isEdgeGlide = false;
        edgeGlidePending = false;
        edgeGlideEligibleSinceMs = 0L;
        lastRawY = Float.NaN;
        // Reset Snap-to-Right-Angle highlight & state on touch end (FR #72 companion).
        resetRightAngleSnapState();
        // Keep user-adjusting true for a short idle window to suppress auto re-detection
        scheduleAdjustIdleClear();
        updateSystemGestureExclusion();
        invalidate();
        break;
    }

    return super.onTouchEvent(event);
  }

  @Override
  public boolean performClick() {
    // Call the super implementation to ensure proper accessibility handling
    return super.performClick();
  }

  /**
   * Find the index of the corner that was touched
   *
   * @param x X coordinate of the touch point
   * @param y Y coordinate of the touch point
   * @return Index of the touched corner, or -1 if no corner was touched
   */
  private int findCornerIndex(float x, float y) {
    for (int i = 0; i < 4; i++) {
      float dx = x - corners[i].x;
      float dy = y - corners[i].y;
      float distance = (float) Math.sqrt(dx * dx + dy * dy);

      if (distance < CORNER_TOUCH_RADIUS) {
        return i;
      }
    }

    return -1;
  }

  /**
   * Find the index of the quadrilateral edge (0..3) that was touched, or {@code -1} if none. Pure
   * delegation to {@link CropEdgeGeometry#findEdgeHit} with the current corner positions and a
   * dp-converted hit radius.
   */
  private int findEdgeHitIndex(float x, float y) {
    if (corners == null || corners.length < 4) return -1;
    float[] xs = new float[4];
    float[] ys = new float[4];
    for (int i = 0; i < 4; i++) {
      xs[i] = corners[i].x;
      ys[i] = corners[i].y;
    }
    float density = getResources().getDisplayMetrics().density;
    float radiusPx = EDGE_TOUCH_RADIUS_DP * density;
    return CropEdgeGeometry.findEdgeHit(
        xs, ys, x, y, radiusPx, CropEdgeGeometry.EDGE_END_DEADZONE_DEFAULT);
  }

  /**
   * Get the corners of the trapezoid as OpenCV Points
   *
   * @return Array of 4 OpenCV Points representing the corners
   */
  public Point[] getCorners() {
    Point[] points = new Point[4];
    for (int i = 0; i < 4; i++) {
      points[i] = new Point(corners[i].x, corners[i].y);
    }
    return points;
  }

  /**
   * Set the trapezoid corners from image coordinates (pixel space of the current display bitmap,
   * i.e. the bitmap previously passed to {@link #setImageBitmap(Bitmap)}).
   *
   * <p>This is used by the Re-Edit flow (FR #72) to restore the previously accepted trapezoid when
   * the user re-enters the crop screen from the export preview. The view must already have a
   * non-zero size and a current image bitmap; otherwise the call is deferred via {@link
   * #post(Runnable)} until the next layout pass.
   *
   * <p>After applying the corners, auto-initialization (edge detection / DocQuad) is suppressed for
   * the next bitmap event so that the restored selection is not overwritten.
   *
   * @param imageCorners 4 points in image pixel coordinates of the current display bitmap; order
   *     [TL, TR, BR, BL]. A null or invalid array is ignored.
   */
  public void setCornersFromImageCoordinates(Point[] imageCorners) {
    if (imageCorners == null || imageCorners.length != 4) {
      Log.w(TAG, "setCornersFromImageCoordinates: invalid input");
      return;
    }
    final Point[] copy = new Point[4];
    for (int i = 0; i < 4; i++) {
      if (imageCorners[i] == null) {
        Log.w(TAG, "setCornersFromImageCoordinates: null point at index " + i);
        return;
      }
      copy[i] = new Point(imageCorners[i].x, imageCorners[i].y);
    }
    final int viewW = getWidth();
    final int viewH = getHeight();
    if (viewW <= 0 || viewH <= 0 || imageBitmap == null) {
      // Defer until layout/bitmap available.
      post(() -> setCornersFromImageCoordinates(copy));
      return;
    }
    if (!isValidImageQuad(copy, imageBitmap.getWidth(), imageBitmap.getHeight())) {
      Log.w(TAG, "setCornersFromImageCoordinates: invalid or degenerate image-space quad");
      return;
    }
    try {
      Point[] viewPts =
          CoordinateTransformUtils.transformImageToViewCoordinates(copy, imageBitmap, viewW, viewH);
      if (viewPts == null || viewPts.length != 4) {
        Log.w(TAG, "setCornersFromImageCoordinates: transform returned invalid result");
        return;
      }
      for (int i = 0; i < 4; i++) {
        corners[i].x = (float) viewPts[i].x;
        corners[i].y = (float) viewPts[i].y;
      }
      for (int i = 0; i < 4; i++) {
        relativeCorners[i] = absoluteToRelative(corners[i].x, corners[i].y, viewW, viewH);
      }
      // Mark as user-edited and initialized so subsequent setImageBitmap calls (e.g. caused
      // by the rotation observer) do not auto-detect and overwrite the restored selection.
      userHasEdited = true;
      initialized = true;
      suppressInitOnce = true;
      try {
        removeCallbacks(initCornersRunnable);
        if (cornerTask != null) {
          cornerTask.cancel(true);
          cornerTask = null;
        }
        requestedInitSeq++;
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      notifyCornersChanged();
      invalidate();
    } catch (Throwable t) {
      Log.w(TAG, "setCornersFromImageCoordinates failed: " + t.getMessage(), t);
    }
  }

  /**
   * The bitmap instance for which corner auto-initialization was last scheduled. Used to skip
   * redundant detection runs when the same bitmap is set again (e.g. view setup followed by the
   * ViewModel observer delivering the identical instance).
   */
  private Bitmap lastAutoInitBitmap;

  /**
   * Set the image bitmap for edge detection
   *
   * @param bitmap The image bitmap
   */
  public void setImageBitmap(Bitmap bitmap) {
    this.imageBitmap = bitmap;
    Log.d(
        TAG,
        "Image bitmap set: "
            + (bitmap != null ? bitmap.getWidth() + "x" + bitmap.getHeight() : "null"));

    // Update last bitmap dimensions; if unchanged and already initialized, avoid redundant
    // auto-init
    if (bitmap != null) {
      int bw = bitmap.getWidth();
      int bh = bitmap.getHeight();
      boolean sameDims = (bw == lastBitmapWidth && bh == lastBitmapHeight);

      // New image (or dimension change) should allow fresh auto-init again.
      if (!sameDims) {
        userHasEdited = false;
        docQuadAutoInitConsumed = false;
        lastAutoInitBitmap = null;
      }

      lastBitmapWidth = bw;
      lastBitmapHeight = bh;
      if (sameDims && initialized) {
        if (debugLogsEnabled)
          Log.d(
              TAG, "setImageBitmap: same bitmap dimensions and already initialized → skip re-init");
        invalidate();
        return;
      }
    } else {
      lastBitmapWidth = -1;
      lastBitmapHeight = -1;
      userHasEdited = false;
      docQuadAutoInitConsumed = false;
      lastAutoInitBitmap = null;
    }

    // If the bitmap is null, do not (re)start corner initialization. Just cancel pending work and
    // redraw.
    if (bitmap == null) {
      // Cancel any pending or running initialization tasks
      try {
        removeCallbacks(initCornersRunnable);
        if (cornerTask != null) {
          cornerTask.cancel(true);
          cornerTask = null;
        }
        requestedInitSeq++; // invalidate any in-flight tasks
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      invalidate();
      return;
    }

    // Initialize OpenCV if needed
    if (!OpenCVUtils.isInitialized()) {
      Log.d(TAG, "Initializing OpenCV for edge detection");
      OpenCVUtils.init(getContext());
    }

    // (Re)trigger async init when we have dimensions (unless suppressed once)
    if (getWidth() > 0 && getHeight() > 0) {
      // If the user is currently adjusting, suppress auto-init for now
      if (isUserAdjusting) {
        suppressInitOnce = true;
        if (debugLogsEnabled)
          Log.d(TAG, "setImageBitmap: suppressing auto-init during user adjustment");
        invalidate();
        return;
      }
      if (bitmap == lastAutoInitBitmap) {
        // Same bitmap instance already triggered (or is running) corner initialization —
        // avoid a redundant detection pass.
        if (debugLogsEnabled)
          Log.d(TAG, "setImageBitmap: same bitmap instance, auto-init already scheduled → skip");
        invalidate();
        return;
      }
      removeCallbacks(initCornersRunnable);
      if (!suppressInitOnce) {
        lastAutoInitBitmap = bitmap;
        // Debounced like the onSizeChanged path: if a fresh layout pass (inset/IME size jump) posts
        // another initialization shortly after, removeCallbacks coalesces them into one run.
        postDelayed(initCornersRunnable, INIT_DEBOUNCE_MS);
      } else {
        suppressInitOnce = false;
        invalidate();
      }
    }
  }

  /**
   * Rotates the current trapezoid corners around the view center by the given clockwise degrees and
   * sets the new image bitmap without re-running corner initialization. If the view is not yet
   * initialized, this falls back to a normal setImageBitmap call.
   */
  public void setImageBitmapWithRotation(@NonNull Bitmap rotatedBitmap, int degreesCw) {
    // Normalize degrees to [0,360)
    int deg = ((degreesCw % 360) + 360) % 360;

    // If view not ready, just set normally
    if (!initialized || getWidth() <= 0 || getHeight() <= 0) {
      setImageBitmap(rotatedBitmap);
      return;
    }

    // If no actual rotation, just swap bitmap without re-detecting
    if (deg == 0) {
      suppressInitOnce = true;
      setImageBitmap(rotatedBitmap);
      invalidate();
      return;
    }

    try {
      // Preconditions: need current image bitmap to map between view<->image
      Bitmap curBmp = this.imageBitmap != null ? this.imageBitmap : rotatedBitmap;
      if (curBmp == null || curBmp.isRecycled()) {
        // Fallback: rotate in view space
        rotateCornersAroundViewCenter(deg);
        suppressInitOnce = true;
        setImageBitmap(rotatedBitmap);
        invalidate();
        return;
      }

      int viewW = getWidth();
      int viewH = getHeight();
      int imgW = curBmp.getWidth();
      int imgH = curBmp.getHeight();

      // 1) View -> Image coordinates for current corners
      org.opencv.core.Point[] viewPts = new org.opencv.core.Point[4];
      for (int i = 0; i < 4; i++)
        viewPts[i] = new org.opencv.core.Point(corners[i].x, corners[i].y);
      org.opencv.core.Point[] imgPts =
          CoordinateTransformUtils.transformViewToImageCoordinates(viewPts, curBmp, viewW, viewH);
      if (imgPts == null)
        throw new IllegalStateException("transformViewToImageCoordinates returned null");

      // 2) Rotate image points around image center and translate to new top-left (0,0)
      double rad = Math.toRadians(deg);
      double cos = Math.cos(rad);
      double sin = Math.sin(rad);
      double cx = imgW / 2.0;
      double cy = imgH / 2.0;

      // Rotate the original image rectangle's corners to compute bounds translation
      double[][] rect = new double[][] {{0, 0}, {imgW, 0}, {imgW, imgH}, {0, imgH}};
      double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
      double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
      for (double[] p : rect) {
        double dx = p[0] - cx;
        double dy = p[1] - cy;
        double rx = cx + dx * cos + dy * sin; // CW
        double ry = cy + (-dx * sin + dy * cos);
        if (rx < minX) minX = rx;
        if (ry < minY) minY = ry;
        if (rx > maxX) maxX = rx;
        if (ry > maxY) maxY = ry;
      }
      double transX = -minX;
      double transY = -minY;
      int newImgW = (int) Math.round(maxX - minX);
      int newImgH = (int) Math.round(maxY - minY);

      org.opencv.core.Point[] imgPtsRot = new org.opencv.core.Point[4];
      for (int i = 0; i < 4; i++) {
        double dx = imgPts[i].x - cx;
        double dy = imgPts[i].y - cy;
        double rx = cx + dx * cos + dy * sin;
        double ry = cy + (-dx * sin + dy * cos);
        // Translate into rotated-bitmap coordinate system (top-left at 0,0)
        imgPtsRot[i] = new org.opencv.core.Point(rx + transX, ry + transY);
      }

      // 3) Image -> View coordinates using the rotated bitmap's dimensions
      // We don't need the actual ImageView; mapping uses viewW/viewH with FIT_CENTER assumption
      // Create a lightweight stub bitmap size using rotatedBitmap/newImgW,newImgH; prefer
      // rotatedBitmap if valid
      Bitmap basis =
          rotatedBitmap != null
              ? rotatedBitmap
              : Bitmap.createBitmap(
                  Math.max(1, newImgW), Math.max(1, newImgH), Bitmap.Config.ARGB_8888);
      org.opencv.core.Point[] viewPtsNew =
          CoordinateTransformUtils.transformImageToViewCoordinates(imgPtsRot, basis, viewW, viewH);
      if (viewPtsNew == null)
        throw new IllegalStateException("transformImageToViewCoordinates returned null");

      // 4) Update absolute and relative corners
      for (int i = 0; i < 4; i++) {
        corners[i].x = (float) viewPtsNew[i].x;
        corners[i].y = (float) viewPtsNew[i].y;
      }
      for (int i = 0; i < 4; i++) {
        relativeCorners[i] = absoluteToRelative(corners[i].x, corners[i].y, viewW, viewH);
      }

      // 5) Suppress re-init and set new bitmap
      suppressInitOnce = true;
      setImageBitmap(rotatedBitmap);
      invalidate();
    } catch (Throwable t) {
      Log.w(
          TAG,
          "setImageBitmapWithRotation: precise mapping failed, falling back to view-space rotation: "
              + t.getMessage());
      // Fallback: rotate selection around view center
      rotateCornersAroundViewCenter(deg);
      suppressInitOnce = true;
      setImageBitmap(rotatedBitmap);
      invalidate();
    }
  }

  private void rotateCornersAroundViewCenter(int degreesCw) {
    int deg = ((degreesCw % 360) + 360) % 360;
    if (deg == 0) return;
    float cx = getWidth() / 2f;
    float cy = getHeight() / 2f;
    double rad = Math.toRadians(deg);
    float cos = (float) Math.cos(rad);
    float sin = (float) Math.sin(rad);
    for (int i = 0; i < 4; i++) {
      float dx = corners[i].x - cx;
      float dy = corners[i].y - cy;
      float nx = (dx * cos + dy * sin);
      float ny = (-dx * sin + dy * cos);
      corners[i].x = cx + nx;
      corners[i].y = cy + ny;
    }
    int w = getWidth();
    int h = getHeight();
    for (int i = 0; i < 4; i++) {
      relativeCorners[i] = absoluteToRelative(corners[i].x, corners[i].y, w, h);
    }
    // Re-evaluate off-image state after rotation; corners may have crossed the image rect.
    notifyCornersChanged();
  }

  // ===== Magnifier API (public) =====
  public void setMagnifierSourceView(
      @NonNull View imageContentView, @Nullable Matrix imageToOverlayMatrix) {
    this.magnifierSourceView = imageContentView;
    if (imageToOverlayMatrix != null) {
      Matrix inv = new Matrix();
      if (imageToOverlayMatrix.invert(inv)) {
        this.overlayToSource = inv;
      } else {
        this.overlayToSource = null;
        Log.w(
            TAG, "Failed to invert imageToOverlayMatrix; falling back to screen-space transforms.");
      }
    } else {
      this.overlayToSource = null;
    }
    // Rebuild magnifier lazily on next drag
    if (magnifier != null) {
      try {
        magnifier.dismiss();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    magnifier = null;
  }

  // ===== Magnifier helpers (private) =====
  private void ensureMagnifier() {
    if (magnifier == null && magnifierSourceView != null && magnifierEnabled) {
      try {
        Magnifier.Builder builder =
            new Magnifier.Builder(magnifierSourceView)
                .setInitialZoom(magnifierZoom)
                .setSize(magnifierSizePx, magnifierSizePx)
                .setDefaultSourceToMagnifierOffset(0, -(int) (magnifierSizePx * 0.75f));
        magnifier = builder.build();
      } catch (Throwable t) {
        Log.w(TAG, "Failed to create Magnifier: " + t.getMessage());
        magnifier = null;
      }
    }
  }

  private PointF toSourceCoords(float overlayX, float overlayY) {
    if (overlayToSource != null) {
      float[] pts = new float[] {overlayX, overlayY};
      overlayToSource.mapPoints(pts);
      return new PointF(pts[0], pts[1]);
    }
    if (magnifierSourceView != null) {
      int[] srcLoc = new int[2];
      int[] ovlLoc = new int[2];
      magnifierSourceView.getLocationOnScreen(srcLoc);
      this.getLocationOnScreen(ovlLoc);
      float screenX = overlayX + ovlLoc[0];
      float screenY = overlayY + ovlLoc[1];
      return new PointF(screenX - srcLoc[0], screenY - srcLoc[1]);
    }
    return new PointF(overlayX, overlayY);
  }

  /**
   * Force the view to be visible and properly initialized This can be called from outside to ensure
   * the view is in the correct state It's particularly important after orientation changes
   */
  public void forceVisibleAndInitialized() {
    Log.d(TAG, "forceVisibleAndInitialized called");

    // Check if the view is attached to a window
    boolean isAttached = isAttachedToWindow();
    Log.d(TAG, "View is " + (isAttached ? "attached" : "not attached") + " to window");

    // Log current dimensions and orientation
    int currentWidth = getWidth();
    int currentHeight = getHeight();
    int orientation = getResources().getConfiguration().orientation;
    String orientationName =
        (orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
            ? "portrait"
            : "landscape";
    Log.d(
        TAG,
        "Current dimensions: "
            + currentWidth
            + "x"
            + currentHeight
            + ", orientation: "
            + orientationName);

    // Force the view to be visible
    setVisibility(View.VISIBLE);

    // Bring to front to ensure it's on top of the view hierarchy
    bringToFront();

    // Request layout to ensure the view is properly laid out
    requestLayout();

    // If the view is not attached or has zero dimensions, we need to use ViewTreeObserver
    // to wait until the view is properly laid out before initializing or scaling corners
    if (!isAttached || currentWidth == 0 || currentHeight == 0) {
      Log.d(TAG, "View not attached or has zero dimensions, using ViewTreeObserver");

      // Store the current orientation for later comparison
      final int initialOrientation = orientation;

      // Use ViewTreeObserver to wait for layout to complete
      getViewTreeObserver()
          .addOnGlobalLayoutListener(
              new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                  // Remove the listener to prevent multiple calls
                  getViewTreeObserver().removeOnGlobalLayoutListener(this);

                  // Get the current dimensions and orientation after layout
                  int newWidth = getWidth();
                  int newHeight = getHeight();
                  int newOrientation = getResources().getConfiguration().orientation;
                  String newOrientationName =
                      (newOrientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                          ? "portrait"
                          : "landscape";

                  Log.d(
                      TAG,
                      "ViewTreeObserver.onGlobalLayout: dimensions="
                          + newWidth
                          + "x"
                          + newHeight
                          + ", orientation="
                          + newOrientationName);

                  // Check if we have valid dimensions now
                  if (newWidth > 0 && newHeight > 0) {
                    // Handle initialization or scaling based on the current state
                    if (!initialized) {
                      Log.d(TAG, "Initializing corners after layout completion");
                      initializeCornersAsync();
                    } else if (newWidth != lastWidth
                        || newHeight != lastHeight
                        || newOrientation != initialOrientation) {
                      // Dimensions or orientation have changed, scale corners
                      Log.d(
                          TAG,
                          "Scaling corners after layout completion: "
                              + lastWidth
                              + "x"
                              + lastHeight
                              + " -> "
                              + newWidth
                              + "x"
                              + newHeight
                              + ", orientation: "
                              + (initialOrientation
                                      == android.content.res.Configuration.ORIENTATION_PORTRAIT
                                  ? "portrait"
                                  : "landscape")
                              + " -> "
                              + newOrientationName);

                      // Scale each corner based on its relative position
                      for (int i = 0; i < 4; i++) {
                        PointF newPos =
                            relativeToAbsolute(
                                relativeCorners[i][0], relativeCorners[i][1], newWidth, newHeight);
                        corners[i].set(newPos.x, newPos.y);
                      }

                      // Update the last known dimensions
                      lastWidth = newWidth;
                      lastHeight = newHeight;

                      // Log the scaled corners
                      Log.d(
                          TAG,
                          "Corners after scaling for orientation change: "
                              + "("
                              + corners[0].x
                              + ","
                              + corners[0].y
                              + "), "
                              + "("
                              + corners[1].x
                              + ","
                              + corners[1].y
                              + "), "
                              + "("
                              + corners[2].x
                              + ","
                              + corners[2].y
                              + "), "
                              + "("
                              + corners[3].x
                              + ","
                              + corners[3].y
                              + ")");
                    }

                    // Force a redraw
                    invalidate();
                    postInvalidate();
                  } else {
                    // Still don't have valid dimensions, try again with a post
                    Log.w(TAG, "Still have invalid dimensions after layout, scheduling retry");
                    post(() -> forceVisibleAndInitialized());
                  }
                }
              });

      // Also schedule a fallback in case ViewTreeObserver doesn't trigger
      postDelayed(
          () -> {
            if (getWidth() > 0 && getHeight() > 0 && !initialized) {
              Log.d(TAG, "Fallback initialization after delay");
              initializeCornersAsync();
            }
          },
          500);

      return; // Exit early, the rest will be handled in the OnGlobalLayoutListener
    }

    // If we reach here, the view is attached and has valid dimensions

    // Check if we need to initialize corners or just ensure they're properly scaled
    if (!initialized) {
      Log.d(TAG, "View not initialized, initializing corners directly");
      initializeCornersAsync();
    } else if (currentWidth > 0
        && currentHeight > 0
        && (currentWidth != lastWidth || currentHeight != lastHeight)) {
      // Dimensions have changed (likely due to rotation), scale corners
      Log.d(
          TAG,
          "Dimensions changed from "
              + lastWidth
              + "x"
              + lastHeight
              + " to "
              + currentWidth
              + "x"
              + currentHeight
              + ", scaling corners");

      // Scale each corner based on its relative position
      for (int i = 0; i < 4; i++) {
        PointF newPos =
            relativeToAbsolute(
                relativeCorners[i][0], relativeCorners[i][1], currentWidth, currentHeight);
        corners[i].set(newPos.x, newPos.y);
      }

      // Update the last known dimensions
      lastWidth = currentWidth;
      lastHeight = currentHeight;

      // Log the scaled corners
      Log.d(
          TAG,
          "Corners after scaling for orientation change: "
              + "("
              + corners[0].x
              + ","
              + corners[0].y
              + "), "
              + "("
              + corners[1].x
              + ","
              + corners[1].y
              + "), "
              + "("
              + corners[2].x
              + ","
              + corners[2].y
              + "), "
              + "("
              + corners[3].x
              + ","
              + corners[3].y
              + ")");
    } else {
      Log.d(TAG, "View already initialized with correct dimensions, ensuring visibility");
    }

    // Force immediate invalidation
    invalidate();
    postInvalidate();

    // Schedule multiple checks to verify the view state after delays
    // This helps catch issues that might occur during the layout process
    for (int delay : new int[] {100, 300, 500, 1000}) {
      final int checkDelay = delay;
      postDelayed(
          () -> {
            boolean isStillAttached = isAttachedToWindow();
            boolean isVisible = getVisibility() == View.VISIBLE;
            boolean hasValidDimensions = getWidth() > 0 && getHeight() > 0;
            int currentOrientation = getResources().getConfiguration().orientation;

            Log.d(
                TAG,
                "View state verification after "
                    + checkDelay
                    + "ms: "
                    + "attached="
                    + isStillAttached
                    + ", "
                    + "visible="
                    + isVisible
                    + ", "
                    + "dimensions="
                    + getWidth()
                    + "x"
                    + getHeight()
                    + ", "
                    + "orientation="
                    + (currentOrientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                        ? "portrait"
                        : "landscape")
                    + ", "
                    + "initialized="
                    + initialized);

            if (!isVisible || !hasValidDimensions) {
              Log.w(
                  TAG,
                  "View is still not in correct state after "
                      + checkDelay
                      + "ms, applying emergency fixes");

              // Emergency fixes
              setVisibility(View.VISIBLE);
              requestLayout();

              if (!initialized && hasValidDimensions) {
                initializeCornersAsync();
              } else if (initialized
                  && hasValidDimensions
                  && (getWidth() != lastWidth || getHeight() != lastHeight)) {
                // Dimensions have changed again, rescale corners
                Log.d(TAG, "Dimensions changed again in verification check, rescaling corners");
                for (int i = 0; i < 4; i++) {
                  PointF newPos =
                      relativeToAbsolute(
                          relativeCorners[i][0], relativeCorners[i][1], getWidth(), getHeight());
                  corners[i].set(newPos.x, newPos.y);
                }
                lastWidth = getWidth();
                lastHeight = getHeight();
              }

              invalidate();
              postInvalidate();
            }
          },
          delay);
    }
  }
}
