/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.camera;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.*;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.common.util.concurrent.ListenableFuture;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentCameraBinding;
import de.schliweb.makeacopy.framing.*;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.ui.export.ExportPrefsHelper;
import de.schliweb.makeacopy.ui.ocr.OCRViewModel;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.ui.A11yUtils;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.HapticsUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import java.io.File;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The `CameraFragment` class is responsible for managing the camera operations and associated UI
 * interactions within a given fragment in an Android application. It includes functionality for
 * initializing the camera, handling light sensor inputs, managing image capture, and providing a
 * seamless user experience for scanning or capturing images.
 *
 * <p>This fragment utilizes the CameraX library along with experimental interoperability features
 * for advanced camera controls.
 *
 * <p>Key Responsibilities: - Configure and bind various use cases of the camera (e.g., preview,
 * image capture, analysis). - Handle permissions for camera access and provide appropriate error
 * messages if permissions are not granted. - Control the camera state and flashlight based on user
 * interaction and environmental conditions. - Provide feedback and updates to the UI to inform the
 * user during the scanning or image capture process. - Monitor sensor data for low-light conditions
 * and suggest enabling the flashlight when necessary. - Perform image analysis for tasks such as
 * corner detection. Low-light detection uses the ambient light sensor rather than estimating
 * luminance from camera frames. - Manage resources effectively to prevent leaks or unnecessary
 * memory usage.
 *
 * <p>Usage Considerations: - This class requires the necessary camera permissions to be granted
 * before initializing camera functionality. - It manages concurrency and thread-safety for
 * operations such as image capture and analysis. - The UI components must be properly initialized
 * and accessible through its `binding` property.
 *
 * <p>This fragment integrates various techniques such as light sensor monitoring, threading for
 * image analysis, and real-time updates to enhance the camera user experience in dynamic
 * application scenarios.
 */
@SuppressWarnings("FutureReturnValueIgnored") // camera control futures are fire-and-forget
@dagger.hilt.android.AndroidEntryPoint
public class CameraFragment extends Fragment implements SensorEventListener {

  @javax.inject.Inject de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner docQuadOrtRunner;

  private static final String TAG = "CameraFragment";
  private static final String PREF_EXPOSURE_INDEX = "exposure_compensation_index";
  private static final String PREF_CAMERA_ZOOM_RATIO = "camera_zoom_ratio";
  private static final String PREF_MANUAL_FOCUS_PROGRESS = "manual_focus_progress";
  private static final String PREF_MANUAL_FOCUS_POSITION = "manual_focus_position";
  private static final int FOCUS_SLIDER_MIN = 1;
  private static final int FOCUS_SLIDER_MAX = 100;
  private static final int FOCUS_SLIDER_DEFAULT_POSITION = 50;
  // Tap-to-focus metering point sizes (fraction of the preview's narrower dimension).
  // AF uses a small region so focus really locks on the tapped spot; AE uses a larger
  // region for stable document exposure. AWB is intentionally not metered to avoid
  // white-balance jumps on paper.
  private static final float TAP_TO_FOCUS_AF_POINT_SIZE = 0.07f;
  private static final float TAP_TO_FOCUS_AE_POINT_SIZE = 0.25f;

  // Live corner detection: cache detector instance to make DocQuad caching/throttle effective.
  // Important: we must NOT instantiate any DocQuad/ORT objects when the prod flag is OFF.
  private volatile de.schliweb.makeacopy.ml.corners.CornerDetector cachedLiveCornerDetector = null;
  private volatile boolean cachedLiveCornerDetectorFlag = false;

  // Live-analysis allocation guardrails (avoid per-frame large allocations on analyzer thread)
  // ThreadLocal because CameraX analyzer runs on a dedicated background thread.
  @SuppressWarnings("ThreadLocalUsage") // intentional: reuse buffers on CameraX analyzer thread
  private final ThreadLocal<byte[]> nv21ReuseBuffer = new ThreadLocal<>();

  @SuppressWarnings("ThreadLocalUsage") // intentional: reuse stream on CameraX analyzer thread
  private final ThreadLocal<java.io.ByteArrayOutputStream> jpegReuseStream = new ThreadLocal<>();

  // Bitmap pool for the small "upright" detection bitmap produced by yuvToBitmapUprightSmall.
  // Two slots cover both orientations (portrait/landscape) of DETECTION_MAX_EDGE-sized images.
  // Reusing bitmaps avoids per-frame allocations of ~720x540 ARGB_8888 (~1.5 MB) and reduces GC.
  @SuppressWarnings("ThreadLocalUsage") // intentional: per analyzer thread
  private final ThreadLocal<Bitmap> uprightBitmapPoolA = new ThreadLocal<>();

  @SuppressWarnings("ThreadLocalUsage") // intentional: per analyzer thread
  private final ThreadLocal<Bitmap> uprightBitmapPoolB = new ThreadLocal<>();

  // Reusable OpenCV Mats for the fast NV21 → RGBA path (avoid per-frame Mat allocations).
  @SuppressWarnings("ThreadLocalUsage") // intentional: per analyzer thread
  private final ThreadLocal<org.opencv.core.Mat> nv21MatTL = new ThreadLocal<>();

  @SuppressWarnings("ThreadLocalUsage") // intentional: per analyzer thread
  private final ThreadLocal<org.opencv.core.Mat> rgbaMatTL = new ThreadLocal<>();

  @SuppressWarnings("ThreadLocalUsage") // intentional: per analyzer thread
  private final ThreadLocal<org.opencv.core.Mat> resizedMatTL = new ThreadLocal<>();

  @SuppressWarnings("ThreadLocalUsage") // intentional: per analyzer thread
  private final ThreadLocal<org.opencv.core.Mat> rotatedMatTL = new ThreadLocal<>();

  // Light sensor constants
  private static final float LOW_LIGHT_THRESHOLD = 10.0f; // lux
  private static final long MIN_TIME_BETWEEN_PROMPTS = 60000; // ms
  // Tuning: minimum number of valid frames before overlay appears; tolerated gap before hiding
  // again
  private static final int OVERLAY_SHOW_AFTER_VALID = 2; // at least 2 consecutive valid frames
  private static final int OVERLAY_HIDE_AFTER_INVALID =
      3; // hide only after 3 consecutive invalid frames
  private static final float CORNER_EMA_ALPHA = 0.25f; // higher = more reactive (0..1)
  private static final double SCORE_EMA_ALPHA = 0.25; // same as above, for score
  // Threshold for the live score below which an explicit "No document detected" hint is announced
  // (A11y).
  // Score range: 0..1
  private FragmentCameraBinding binding;
  private CameraViewModel cameraViewModel;
  private CropViewModel cropViewModel;
  private ImageCapture imageCapture;
  private ProcessCameraProvider cameraProvider;
  private Camera camera;
  private Preview preview;
  private float selectedZoomRatio = CameraZoomOptions.DEFAULT_ZOOM_RATIO;
  private float minimumFocusDistanceDiopters = 0f;
  private boolean manualFocusSupported = false;
  // True while the user has selected manual focus via the slider; false means autofocus.
  // The AF/MF split (separate AF button + MF-only slider) was requested in issue #78.
  private boolean manualFocusActive = false;
  // Live manual-focus updates while dragging the slider (throttled to avoid flooding the
  // capture session with Camera2 interop requests).
  private static final long MANUAL_FOCUS_LIVE_UPDATE_INTERVAL_MS = 100L;
  private int pendingFocusProgress = 0;
  private boolean focusUpdateScheduled = false;
  // The "manual focus locked" toast is shown only after the slider drag has really ended:
  // onStopTrackingTouch can fire multiple times per gesture (e.g. ACTION_CANCEL when the finger
  // leaves the slider bounds), so the toast is posted with a short delay and cancelled if
  // dragging resumes (issue #78 feedback).
  private Runnable pendingFocusLockedToast = null;
  private static final long MANUAL_FOCUS_TOAST_DELAY_MS = 400L;
  private int tapToFocusRequestId = 0;
  private boolean isFlashlightOn = false;
  private boolean hasFlash = false;
  // Live corner preview (document trapezoid)
  private ImageAnalysis imageAnalysis;
  private ExecutorService analysisExecutor;
  private volatile boolean analysisEnabled = false;
  private long lastAnalysisTs = 0L;
  // Live focus-quality (sharpness) indicator — feature-flagged, disabled by default.
  // See docs/focus_quality_indicator_design.md. The analyzer is created lazily so the
  // feature has zero overhead while the flag is off; the meter holds the decaying
  // rolling-max normalization state (analyzer thread only).
  private FocusQualityAnalyzer focusQualityAnalyzer = null;
  private final FocusQualityMeter focusQualityMeter = new FocusQualityMeter();
  private volatile int lastFocusQualitySegments = -1;
  private FocusQualityMeter.Band lastAnnouncedFocusQualityBand = null;
  private long lastFocusQualityAnnounceTs = 0L;
  private static final long FOCUS_QUALITY_ANNOUNCE_MIN_INTERVAL_MS = 3000L;
  // Accessibility Mode – stability and feedback state
  private double lastScore = 0.0;
  private int stableCount = 0;
  private long lastA11ySignalTs = 0L;
  // Accessibility Mode – additional debounce markers
  private long lastA11yReadyAnnounceTs = 0L;
  private long lastA11yLowLightTs = 0L;
  private long lastVolumeShutterTs = 0L;
  private long lastA11yVolumeHintTs = 0L;
  // Overlay stabilization (jitter reduction)
  // Exponential smoothing for corners + score and hysteresis for visibility
  private android.graphics.PointF[] lastFilteredCorners = null; // in view coordinates
  private double lastScoreEma = -1.0; // <0 means: uninitialized
  private int consecutiveValidFrames = 0;
  private int consecutiveInvalidFrames = 0;
  // Light sensor
  private SensorManager sensorManager;
  private Sensor lightSensor;
  private boolean hasLightSensor = false;
  private boolean lowLightPromptShown = false;
  private long lastPromptTime = 0;
  private boolean isLowLightDialogVisible = false;
  private ActivityResultLauncher<String> requestPermissionLauncher;
  private ActivityResultLauncher<Intent> pickImageLauncher;
  private OrientationEventListener orientationListener;
  private boolean reinitScheduled = false;
  private boolean streamObserverAttached = false;
  private BindTier lastTier = null;
  private AccessibilityGuidanceController a11yGuidanceController;
  // New state machine for accessibility guidance (concept-based implementation)
  private A11yStateMachine a11yStateMachine;
  // Let both ScreenReader and Debug-Overlay follow the exact same cadence:
  // we persist the last hint emitted by the AccessibilityGuidanceController.
  private volatile GuidanceHint lastGuidanceEventHint = null;

  // Model-free distance estimation via autofocus (Camera2 API)
  // Value in diopters (1/meters). 0 or negative means unavailable.
  @SuppressWarnings("UnusedVariable") // explicit init required for volatile field
  private volatile float lastFocusDistanceDiopters = 0f;

  // Lens diagnostics: last reported active physical camera id (logical multi-camera, API 29+)
  // and last reported focal length. Used to log lens switches when the zoom ratio changes.
  private volatile String lastActivePhysicalCameraId = null;
  private volatile float lastLensFocalLength = 0f;

  // Explicit lens selection (issue #75): back lenses resolved once per camera provider, plus the
  // currently bound dedicated lens (null = default back camera) and its zoom factor relative to
  // the default back camera. Some devices (e.g. Pixel 6 Pro) never switch to the telephoto lens
  // via CONTROL_ZOOM_RATIO on the logical camera, so 3x/5x presets bind the tele camera directly.
  private java.util.List<CameraLensSelector.LensInfo> backLenses = null;
  private String boundLensCameraId = null;
  private float boundLensZoomFactor = 1.0f;

  /**
   * Converts a given surface rotation value to its corresponding degree representation.
   *
   * @param surfaceRotation the surface rotation value, typically one of the predefined constants
   *     (e.g., Surface.ROTATION_0, Surface.ROTATION_90, etc.).
   * @return the equivalent degree value for the given rotation: 0, 90, 180, or 270. Returns 0 for
   *     unrecognized values.
   */
  private static int toDegrees(int surfaceRotation) {
    switch (surfaceRotation) {
      case Surface.ROTATION_0:
        return 0;
      case Surface.ROTATION_90:
        return 90;
      case Surface.ROTATION_180:
        return 180;
      case Surface.ROTATION_270:
        return 270;
      default:
        return 0;
    }
  }

  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);
    cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
    registerLaunchers();

    binding = FragmentCameraBinding.inflate(inflater, container, false);
    View root = binding.getRoot();

    // Verbose environment log to help diagnose device-specific issues
    logEnvironment();
    setupInsets(root);

    // Init UI visibility
    showCameraMode();

    // If MainActivity received a shared image/PDF (ACTION_SEND / ACTION_VIEW),
    // route it through the existing import pipeline. Posted to the view so that
    // the navigation graph and bindings are fully ready.
    consumePendingShareIfAny();

    // DocQuad runner is now injected via Hilt (docQuadOrtRunner field).
    // No proactive loading needed — the singleton is created eagerly by the DI container.

    final TextView textView = binding.textCamera;
    cameraViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

    setupCaptureControls();
    // Pick image button (supports images and PDFs)
    binding.buttonPickImage.setOnClickListener(v -> launchImportPicker());
    binding.buttonCameraOptions.setOnClickListener(v -> showCameraOptions());
    setupVolumeKeyShutter(root);
    setupLibraryButton();
    setupReviewButtons();

    // Camera permission
    cameraViewModel
        .isCameraPermissionGranted()
        .observe(
            getViewLifecycleOwner(),
            granted -> {
              if (granted) {
                initializeCamera();
              }
            });
    checkCameraPermission();

    // Only capability detection here; registration happens in onResume (3)
    initLightSensor();
    setupBackHandling();

    // Session 3: offer resuming an unfinished persisted document (once per process start).
    maybeOfferDocumentResume();

    return root;
  }

  private void registerLaunchers() {
    requestPermissionLauncher =
        registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
              Log.i(TAG, "Camera permission result: " + (isGranted ? "GRANTED" : "DENIED"));
              if (isGranted) {
                cameraViewModel.setCameraPermissionGranted(true);
              } else {
                UIUtils.showToast(
                    requireContext(), R.string.msg_camera_permission_required, Toast.LENGTH_LONG);
              }
            });

    // Register the image/PDF picker launcher (SAF)
    pickImageLauncher =
        registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::onImportPicked);
  }

  /** Result of the image/PDF picker: remember the folder and route the file into the import. */
  private void onImportPicked(androidx.activity.result.ActivityResult result) {
    if (result == null || result.getData() == null) return;
    if (result.getResultCode() != android.app.Activity.RESULT_OK) return;
    Intent data = result.getData();
    Uri uri = data.getData();
    if (uri == null) return;

    // Determine MIME type
    String mime = requireContext().getContentResolver().getType(uri);
    if (mime == null) {
      UIUtils.showToast(requireContext(), R.string.error_unknown_file_type, Toast.LENGTH_SHORT);
      return;
    }

    // Persist read permission if granted by the chooser (ignore if not allowed)
    int takeFlags =
        data.getFlags()
            & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    if ((takeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
      try {
        requireContext()
            .getContentResolver()
            .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (SecurityException se) {
        Log.w(TAG, "Persistable read permission not granted by provider", se);
      }
    }

    // Derive a parent/folder URI for the picker's initial location hint.
    // Using the raw file URI often fails on OEM pickers (Xiaomi, Samsung, etc.).
    Uri folderHint =
        de.schliweb.makeacopy.utils.infra.DocumentUriUtils.deriveParentDocumentUri(uri);
    // Save folder URI if derivable, otherwise fall back to the original URI.
    // Even a file URI is better than nothing — some pickers still respect it.
    ExportPrefsHelper.setLastImportUri(
        requireContext(), folderHint != null ? folderHint.toString() : uri.toString());

    if (mime.startsWith("image/")) {
      // Handle image import (existing logic)
      handleImageImport(uri);
    } else if (mime.equals("application/pdf")) {
      // Handle PDF import
      handlePdfImport(uri);
    } else {
      UIUtils.showToast(requireContext(), R.string.error_unsupported_file_type, Toast.LENGTH_SHORT);
    }
  }

  private void setupInsets(View root) {
    ViewCompat.setOnApplyWindowInsetsListener(
        binding.buttonContainer,
        (v, insets) -> {
          UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 8);
          return insets;
        });
    ViewCompat.setOnApplyWindowInsetsListener(
        binding.scanButtonContainer,
        (v, insets) -> {
          UIUtils.adjustMarginForSystemInsets(binding.scanButtonContainer, 8);
          return insets;
        });
    // Insets (Status bar)
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
          ViewGroup.MarginLayoutParams textParams =
              (ViewGroup.MarginLayoutParams) binding.textCamera.getLayoutParams();
          textParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density) + topInset;
          binding.textCamera.setLayoutParams(textParams);
          return insets;
        });
  }

  /** Shutter, flashlight, zoom and tap-to-focus. */
  private void setupCaptureControls() {
    // Scan
    binding.buttonScan.setOnClickListener(
        v -> {
          if (cameraViewModel != null
              && cameraViewModel.isCameraPermissionGranted().getValue().equals(Boolean.TRUE)) {
            captureImage();
          } else {
            checkCameraPermission();
          }
        });

    // Set up flashlight button
    binding.buttonFlash.setOnClickListener(v -> toggleFlashlight());

    selectedZoomRatio = readPersistedZoomRatio();
    updateZoomButtonLabel(selectedZoomRatio);
    binding.buttonCameraZoom.setOnClickListener(v -> showZoomPicker());
    setupTapToFocus();
  }

  private void launchImportPicker() {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    // Accept both images and PDFs
    intent.setType("*/*");
    intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "application/pdf"});
    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

    // Restore last import folder location if available.
    // EXTRA_INITIAL_URI is only a hint — OEM pickers may ignore it.
    String lastImportUri = ExportPrefsHelper.getLastImportUri(requireContext());
    if (lastImportUri != null) {
      try {
        Uri initialUri = Uri.parse(lastImportUri);
        intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri);
      } catch (Exception e) {
        Log.w(TAG, "Ignoring invalid last import URI", e);
      }
    }

    pickImageLauncher.launch(intent);
  }

  private void showCameraOptions() {
    getParentFragmentManager()
        .setFragmentResultListener(
            CameraOptionsDialogFragment.REQUEST_KEY,
            getViewLifecycleOwner(),
            (requestKey, bundle) -> {
              onCameraOptionsResult(bundle);
              getParentFragmentManager()
                  .clearFragmentResultListener(CameraOptionsDialogFragment.REQUEST_KEY);
            });
    CameraOptionsDialogFragment.show(getParentFragmentManager());
  }

  private void onCameraOptionsResult(Bundle bundle) {
    boolean skip = bundle.getBoolean(CameraOptionsDialogFragment.BUNDLE_SKIP_OCR, false);
    boolean analysisPref =
        bundle.getBoolean(CameraOptionsDialogFragment.BUNDLE_ANALYSIS_ENABLED, true);
    boolean a11yPref =
        bundle.getBoolean(CameraOptionsDialogFragment.BUNDLE_ACCESSIBILITY_MODE, false);
    boolean exposurePref =
        bundle.getBoolean(CameraOptionsDialogFragment.BUNDLE_EXPOSURE_COMPENSATION, false);
    boolean manualFocusPref =
        bundle.getBoolean(CameraOptionsDialogFragment.BUNDLE_MANUAL_FOCUS, false);
    Context ctx = getContext();
    if (ctx != null) {
      android.content.SharedPreferences prefs =
          ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
      prefs
          .edit()
          .putBoolean("skip_ocr", skip)
          .putBoolean("analysis_enabled", analysisPref)
          // Accessibility is already persisted by the dialog; keep a mirror for
          // local reads if needed
          .putBoolean(CameraOptionsDialogFragment.BUNDLE_ACCESSIBILITY_MODE, a11yPref)
          .apply();
    }
    // Apply the toggles immediately if we are in camera mode
    if (binding != null && binding.viewFinder.getVisibility() == View.VISIBLE) {
      applyCameraOptionsToLivePreview(analysisPref, exposurePref, manualFocusPref);
    }
  }

  private void applyCameraOptionsToLivePreview(
      boolean analysisPref, boolean exposurePref, boolean manualFocusPref) {
    setLiveAnalysisEnabled(analysisPref);
    // Apply exposure compensation toggle immediately
    if (exposurePref) {
      setupExposureCompensation();
    } else {
      binding.exposureControl.setVisibility(View.GONE);
      // Reset EV to 0 when feature is disabled
      applyExposureCompensation(0);
      persistExposureIndex(0);
    }
    if (manualFocusPref) {
      setupManualFocusControl();
    } else {
      resetManualFocusControl();
    }
    // Re-assert focus so volume keys are captured again after closing dialog
    binding.getRoot().setFocusableInTouchMode(true);
    binding.getRoot().requestFocus();
    // If Accessibility Mode was enabled, give a one-time ready announcement
    // (debounced)
    if (isAccessibilityModeEnabled()) {
      long now = System.currentTimeMillis();
      if (now - lastA11yReadyAnnounceTs > 4000L) {
        lastA11yReadyAnnounceTs = now;
        announce(R.string.a11y_camera_ready);
      }
    }
  }

  /** Intercept hardware volume keys in Accessibility Mode to trigger shutter. */
  private void setupVolumeKeyShutter(View root) {
    root.setFocusableInTouchMode(true);
    root.requestFocus();
    root.setOnKeyListener((v, keyCode, event) -> onVolumeKey(v, keyCode, event));
  }

  private boolean onVolumeKey(View v, int keyCode, KeyEvent event) {
    if (!isAccessibilityModeEnabled()) return false;
    if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)
      return false;
    if (event.getAction() != KeyEvent.ACTION_DOWN)
      return true; // consume UP as well by returning true on DOWN

    long now = System.currentTimeMillis();
    if (now - lastVolumeShutterTs < 800L) return true; // debounce
    lastVolumeShutterTs = now;

    // Haptic feedback on key press
    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);

    // Only trigger if not already processing and camera is visible
    boolean cameraVisible = binding != null && binding.viewFinder.getVisibility() == View.VISIBLE;
    boolean ready = binding != null && binding.buttonScan.isEnabled();
    if (cameraVisible && ready) {
      captureImage();
    }
    return true; // consume to suppress actual volume change
  }

  /** Library entry from Camera screen (feature-gated). */
  private void setupLibraryButton() {
    if (!FeatureFlags.isScanLibraryEnable()) {
      binding.buttonOpenLibraryCam.setVisibility(View.GONE);
      return;
    }
    binding.buttonOpenLibraryCam.setVisibility(View.VISIBLE);
    binding.buttonOpenLibraryCam.setOnClickListener(
        v -> {
          try {
            Navigation.findNavController(requireView()).navigate(R.id.navigation_scans_library);
          } catch (IllegalArgumentException | IllegalStateException ex) {
            Log.w(TAG, "Navigation to scans library failed", ex);
          }
        });
  }

  /** Retake and confirm (continue) of the captured-image review. */
  private void setupReviewButtons() {
    binding.buttonRetake.setOnClickListener(
        v -> {
          if (isAdded() && binding != null) {
            v.setEnabled(false);
            UIUtils.showToast(requireContext(), R.string.resetting_camera, Toast.LENGTH_SHORT);
            new Handler(Looper.getMainLooper())
                .postDelayed(
                    () -> {
                      resetCamera();
                      new Handler(Looper.getMainLooper())
                          .postDelayed(
                              () -> {
                                if (isAdded() && binding != null) {
                                  binding.buttonRetake.setEnabled(true);
                                }
                              },
                              1000);
                    },
                    100);
          }
        });

    // Confirm (continue)
    binding.buttonConfirm.setOnClickListener(
        v -> {
          if (!isAdded()) return;
          cropViewModel.setImageCropped(false);
          int dest = nextScanFlowDestination();
          // Going straight to OCR (cropping skipped): start from a clean OCR state
          if (dest == R.id.navigation_ocr) {
            OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
            ocrVm.resetForNewImage();
          }
          try {
            Navigation.findNavController(requireView()).navigate(dest, null, scanFlowNavOptions());
          } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Best-effort; failure is non-critical
          }
        });
  }

  /** Where the scan flow continues once an image is available, per the skip options. */
  private int nextScanFlowDestination() {
    boolean skipOcr = false;
    boolean skipCropping = false;
    Context ctx = getContext();
    if (ctx != null) {
      android.content.SharedPreferences prefs =
          ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
      skipOcr = prefs.getBoolean("skip_ocr", false);
      skipCropping = prefs.getBoolean("skip_cropping", false);
    }
    return scanFlowDestination(skipCropping, skipOcr);
  }

  @androidx.annotation.VisibleForTesting
  static int scanFlowDestination(boolean skipCropping, boolean skipOcr) {
    if (!skipCropping) return R.id.navigation_crop;
    return skipOcr ? R.id.navigation_export : R.id.navigation_ocr;
  }

  /** Handle back: in review mode -> reset, otherwise default. */
  private void setupBackHandling() {
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(
            getViewLifecycleOwner(),
            new OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (binding != null && binding.capturedImage.getVisibility() == View.VISIBLE) {
                  resetCamera();
                } else {
                  this.setEnabled(false);
                  requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
              }
            });
  }

  /**
   * Session 3: when an active persisted {@link de.schliweb.makeacopy.data.DocumentSession} with at
   * least one resolvable page exists and the runtime session is empty (fresh process / process
   * death), ask the user whether to resume the unfinished document or start a new one. The prompt
   * is shown at most once per process so normal camera startup is never disturbed repeatedly.
   * "Start new" keeps the old session data persisted (nothing is silently deleted) but clears the
   * active pointer.
   */
  private void maybeOfferDocumentResume() {
    if (resumePromptShownThisProcess) return;
    try {
      de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel sessionVm =
          new ViewModelProvider(requireActivity())
              .get(de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel.class);
      java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
          sessionVm.getPages().getValue();
      if (pages != null && !pages.isEmpty()) {
        // Runtime session already carries a document (no process death) — nothing to resume.
        resumePromptShownThisProcess = true;
        return;
      }
      final Context app = requireContext().getApplicationContext();
      new Thread(
              () -> {
                try {
                  de.schliweb.makeacopy.data.DocumentSessionRepository repo =
                      de.schliweb.makeacopy.data.DocumentSessionRepository.get(app);
                  de.schliweb.makeacopy.data.DocumentSession session = repo.getActiveSession();
                  if (session == null) return;
                  java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> resolved =
                      repo.resolveActivePages(
                          de.schliweb.makeacopy.data.CompletedScansRegistry.get(app));
                  if (resolved.isEmpty()) return;
                  final int count = resolved.size();
                  new Handler(Looper.getMainLooper())
                      .post(
                          () -> {
                            if (!isAdded() || resumePromptShownThisProcess) return;
                            resumePromptShownThisProcess = true;
                            AlertDialog dialog =
                                new MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(getString(R.string.resume_document_title))
                                    .setMessage(getString(R.string.resume_document_message, count))
                                    .setPositiveButton(
                                        R.string.resume_document_resume,
                                        (d, w) -> {
                                          try {
                                            Navigation.findNavController(requireView())
                                                .navigate(R.id.navigation_export);
                                          } catch (IllegalArgumentException
                                              | IllegalStateException ex) {
                                            Log.w(TAG, "Resume navigation failed", ex);
                                          }
                                        })
                                    .setNegativeButton(
                                        R.string.resume_document_start_new,
                                        (d, w) ->
                                            new Thread(
                                                    () -> {
                                                      try {
                                                        repo.endActiveSession(false);
                                                      } catch (Throwable t) {
                                                        Log.w(TAG, "endActiveSession failed", t);
                                                      }
                                                    })
                                                .start())
                                    .create();
                            dialog.setOnShowListener(
                                dlg ->
                                    DialogUtils.improveAlertDialogButtonContrastForNight(
                                        dialog, requireContext()));
                            dialog.show();
                          });
                } catch (Throwable t) {
                  Log.w(TAG, "Document resume check failed", t);
                }
              })
          .start();
    } catch (Throwable t) {
      Log.w(TAG, "maybeOfferDocumentResume failed", t);
    }
  }

  /** Session 3: resume prompt guard — shown at most once per app process. */
  private static boolean resumePromptShownThisProcess = false;

  @Override
  public void onResume() {
    super.onResume();
    Log.i(TAG, "onResume: registering listeners (lightSensor=" + hasLightSensor + ")");
    if (hasLightSensor && sensorManager != null && lightSensor != null) {
      sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
    }
    if (orientationListener != null) orientationListener.enable();
  }

  @Override
  public void onPause() {
    super.onPause();
    Log.i(TAG, "onPause: unregistering listeners");
    if (sensorManager != null) sensorManager.unregisterListener(this);
    if (orientationListener != null) orientationListener.disable();
  }

  // --------- Tiered binding without device checks ----------

  private void checkCameraPermission() {
    if (!isAdded()) return;
    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
        != PackageManager.PERMISSION_GRANTED) {
      if (requestPermissionLauncher != null)
        requestPermissionLauncher.launch(Manifest.permission.CAMERA);
    } else {
      if (cameraViewModel != null) cameraViewModel.setCameraPermissionGranted(true);
    }
  }

  private int getViewFinderRotation() {
    if (binding != null && binding.viewFinder.getDisplay() != null) {
      return binding.viewFinder.getDisplay().getRotation();
    }
    return Surface.ROTATION_0;
  }

  private void initializeCamera() {
    if (binding == null || !isAdded()) {
      Log.d(TAG, "initializeCamera: skip (not attached)");
      return;
    }
    Log.i(TAG, "initializeCamera: requesting ProcessCameraProvider...");
    binding.textCamera.setText(R.string.initializing_camera);

    ListenableFuture<ProcessCameraProvider> fut =
        ProcessCameraProvider.getInstance(requireContext());
    fut.addListener(
        () -> {
          try {
            if (binding == null || !isAdded() || getView() == null) return;
            cameraProvider = fut.get();
            if (cameraProvider == null) {
              UIUtils.showToast(
                  requireContext(), R.string.error_camera_provider_null, Toast.LENGTH_SHORT);
              binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
              Log.w(TAG, "initializeCamera: cameraProvider is null");
              return;
            }
            bindWithTier(BindTier.PERF);
            if (camera == null) {
              Log.e(TAG, "initializeCamera: camera is null after bindWithTier, cannot proceed");
              binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
              if (!reinitScheduled) {
                reinitScheduled = true;
                new Handler(Looper.getMainLooper())
                    .postDelayed(
                        () -> {
                          reinitScheduled = false;
                          if (isAdded()
                              && cameraViewModel != null
                              && Boolean.TRUE.equals(
                                  cameraViewModel.isCameraPermissionGranted().getValue())) {
                            initializeCamera();
                          }
                        },
                        3000);
              }
              return;
            }
            hasFlash = camera.getCameraInfo().hasFlashUnit();
            binding.buttonFlash.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
            isFlashlightOn = false;
            binding.buttonFlash.setImageResource(R.drawable.ic_flash_off);
            applySelectedZoomRatio();
            binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
            logCameraCapabilities();
            logLensDiagnostics();
            setupExposureCompensation();
            setupManualFocusControl();
            setupTapToFocus();
          } catch (Exception e) {
            handleCameraInitializationError(e);
          }
        },
        ContextCompat.getMainExecutor(requireContext()));
  }

  private void handleCameraInitializationError(Exception e) {
    if (!isAdded() || binding == null) return;
    Log.e(TAG, "Camera init error: " + e.getMessage(), e);
    UIUtils.showToast(
        requireContext(),
        getString(R.string.error_initializing_camera, e.getMessage()),
        Toast.LENGTH_LONG);
    binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
    if (!reinitScheduled) {
      reinitScheduled = true;
      new Handler(Looper.getMainLooper())
          .postDelayed(
              () -> {
                reinitScheduled = false;
                if (isAdded()
                    && cameraViewModel != null
                    && Boolean.TRUE.equals(
                        cameraViewModel.isCameraPermissionGranted().getValue())) {
                  initializeCamera();
                }
              },
              3000);
    }
  }

  @OptIn(markerClass = ExperimentalCamera2Interop.class)
  private void bindWithTier(BindTier tier) {
    if (binding == null || cameraProvider == null || !isAdded()) return;
    lastTier = tier;

    // ImplMode
    boolean isSony = "sony".equalsIgnoreCase(Build.MANUFACTURER);
    // Android Emulator (goldfish/ranchu) HAL accepts but silently delivers black frames when
    // HQ post-processing / ZSL / forced AE FPS range options are applied. Treat it like Sony.
    boolean isEmulator =
        Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.PRODUCT.contains("sdk")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for");
    PreviewView.ImplementationMode implMode =
        isSony
            ? PreviewView.ImplementationMode.COMPATIBLE
            : (tier == BindTier.PERF
                ? PreviewView.ImplementationMode.PERFORMANCE
                : PreviewView.ImplementationMode.COMPATIBLE);
    binding.viewFinder.setImplementationMode(implMode);
    binding.viewFinder.setScaleType(PreviewView.ScaleType.FIT_CENTER);
    Log.i(
        TAG,
        "bindWithTier: implMode="
            + implMode
            + ", scaleType=FIT_CENTER, isSony="
            + isSony
            + ", isEmulator="
            + isEmulator);

    int rotation = getViewFinderRotation();
    Log.i(TAG, "bindWithTier: tier=" + tier + ", rotation=" + toDegrees(rotation));

    // Preview selector
    ResolutionSelector.Builder rsPrev =
        new ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                new AspectRatioStrategy(
                    AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO));

    if (tier == BindTier.COMPAT_LOWRES) {
      android.util.Size preferredPreview = new android.util.Size(1280, 960);
      rsPrev.setResolutionStrategy(
          new ResolutionStrategy(
              preferredPreview, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER));
    }

    // Capture selector (prefer high resolution)
    ResolutionSelector.Builder rsCap =
        new ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                new AspectRatioStrategy(
                    AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO));
    android.util.Size preferredHigh = new android.util.Size(4032, 3024);
    rsCap.setResolutionStrategy(
        new ResolutionStrategy(
            preferredHigh, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER));

    Preview.Builder previewBuilder =
        new Preview.Builder().setResolutionSelector(rsPrev.build()).setTargetRotation(rotation);

    ImageCapture.Builder captureBuilder =
        new ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) // beste Qualität für OCR
            .setResolutionSelector(rsCap.build())
            .setTargetRotation(rotation)
            .setJpegQuality(98);

    // Interop: konservative FPS, High-Quality Settings ok
    Camera2Interop.Extender<Preview> pExt = new Camera2Interop.Extender<>(previewBuilder);
    Camera2Interop.Extender<ImageCapture> cExt = new Camera2Interop.Extender<>(captureBuilder);

    // Lens diagnostics on the Preview stream: works independently of the live-analysis setting,
    // so lens switches caused by zoom changes are always visible in the logs.
    pExt.setSessionCaptureCallback(
        new android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
          @Override
          public void onCaptureCompleted(
              @NonNull android.hardware.camera2.CameraCaptureSession session,
              @NonNull android.hardware.camera2.CaptureRequest request,
              @NonNull android.hardware.camera2.TotalCaptureResult result) {
            trackLensFromCaptureResult(result);
          }
        });

    // Some Sony devices reject sessions when an explicit AE_TARGET_FPS_RANGE is set.
    // To avoid "Unsupported set of inputs/outputs provided" (endConfigure) we skip forcing FPS on
    // Sony.
    if (!isSony && !isEmulator) {
      android.util.Range<Integer> fps = new android.util.Range<>(15, 30);
      pExt.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
      cExt.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
    } else {
      Log.i(
          TAG,
          "bindWithTier: skipping AE_TARGET_FPS_RANGE (isSony="
              + isSony
              + ", isEmulator="
              + isEmulator
              + ")");
    }

    // Do NOT force CONTROL_AF_MODE via Camera2Interop: interop options override CameraX's own
    // 3A management, so tap-to-focus could never switch the repeating request from
    // CONTINUOUS_PICTURE to AUTO (the AF trigger then only locked the current lens position
    // instead of scanning the tapped region — observed on Pixel 7a). CameraX already uses
    // CONTINUOUS_PICTURE as default AF mode when no focus/metering action is active.

    if (!isSony && !isEmulator) {
      // High-quality post-processing and ZSL toggles can cause session config failures on some Sony
      // devices, and result in black preview frames on the Android Emulator HAL.
      // Apply only on real, non-Sony devices.
      cExt.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
          android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
      cExt.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.EDGE_MODE,
          android.hardware.camera2.CaptureRequest.EDGE_MODE_HIGH_QUALITY);
      cExt.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
          android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
      cExt.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.SHADING_MODE,
          android.hardware.camera2.CaptureRequest.SHADING_MODE_HIGH_QUALITY);
      cExt.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.CONTROL_ENABLE_ZSL, false);
    } else {
      Log.i(
          TAG,
          "bindWithTier: skipping HQ NR/EDGE/CCA/SHADING and ZSL toggle (isSony="
              + isSony
              + ", isEmulator="
              + isEmulator
              + ")");
    }

    imageCapture = captureBuilder.build();
    preview = previewBuilder.build();

    // Analyzer
    setupOrUpdateImageAnalysis(rotation);

    CameraSelector cameraSelector = buildCameraSelectorForZoom();

    // Shared ViewPort so that Preview, ImageAnalysis and ImageCapture are all cropped to the same
    // field of view (WYSIWYG). Without this, AspectRatioStrategy(RATIO_4_3, FALLBACK_RULE_AUTO) is
    // only a hint and CameraX may deliver diverging crops/aspect ratios per use case on some
    // devices — the root cause of device-dependent aspect-ratio mismatches between the live
    // preview, the live corner overlay and the actually captured image.
    ViewPort sharedViewPort = buildSharedViewPort(rotation);

    try {
      cameraProvider.unbindAll();

      // Non-Sony: keep existing sequence Preview → Preview+Analysis → Preview+Analysis+Capture
      // 1) Preview only
      camera = bindUseCases(cameraSelector, sharedViewPort, preview);
      setPreviewSurfaceProviderWithLog(tier);
      logResolutions("Bind: Preview");

      // 2) Preview + Analysis
      try {
        cameraProvider.unbindAll();
        camera = bindUseCases(cameraSelector, sharedViewPort, preview, imageAnalysis);
        setPreviewSurfaceProviderWithLog(tier);
        logResolutions("Bind: Preview+Analysis");

        // 3) Preview + Analysis + Capture
        try {
          cameraProvider.unbindAll();
          camera =
              bindUseCases(cameraSelector, sharedViewPort, preview, imageAnalysis, imageCapture);
          setPreviewSurfaceProviderWithLog(tier);
          logResolutions("Bind: Preview+Analysis+Capture");
        } catch (IllegalArgumentException e3) {
          Log.w(
              TAG,
              "Bind failed for Preview+Analysis+Capture on " + tier + " → fallback Preview+Capture",
              e3);
          cameraProvider.unbindAll();
          camera = bindUseCases(cameraSelector, sharedViewPort, preview, imageCapture);
          setPreviewSurfaceProviderWithLog(tier);
          logResolutions("Fallback: Preview+Capture");
        }
      } catch (IllegalArgumentException e2) {
        Log.w(TAG, "Bind failed for Preview+Analysis on " + tier + " → fallback Preview only", e2);
        cameraProvider.unbindAll();
        camera = bindUseCases(cameraSelector, sharedViewPort, preview);
        setPreviewSurfaceProviderWithLog(tier);
        logResolutions("Fallback: Preview");
      }

      // OrientationListener
      if (orientationListener == null) {
        orientationListener =
            new OrientationEventListener(requireContext()) {
              @Override
              public void onOrientationChanged(int orientation) {
                if (!isAdded() || binding == null) return;
                int rot = getViewFinderRotation();
                if (imageCapture != null) imageCapture.setTargetRotation(rot);
                if (preview != null) preview.setTargetRotation(rot);
                if (imageAnalysis != null) imageAnalysis.setTargetRotation(rot);
              }
            };
        orientationListener.enable();
      }

      attachWatchdogs();

      // Torch handling: no adaptive exposure compensation is performed during live analysis.
      // If the torch is already on, there is no special handling here.
      if (isFlashlightOn) {
        // no-op
      }
      applySelectedZoomRatio();
      setupManualFocusControl();

    } catch (IllegalArgumentException e) {
      Log.e(TAG, "bindToLifecycle failed: " + e.getMessage(), e);
      escalateBindTier();
    }
  }

  /**
   * Builds a shared {@link ViewPort} so that all bound use cases (Preview, ImageAnalysis,
   * ImageCapture) are cropped to the same field of view. Prefers the {@link PreviewView}'s own
   * ViewPort (which mirrors its FIT_CENTER scale type and current size), and falls back to an
   * explicit 4:3 sensor ratio when the view is not laid out yet.
   *
   * @param rotation the target rotation (Surface.ROTATION_*) shared by all use cases
   * @return a ViewPort to attach to the {@link UseCaseGroup}, never {@code null}
   */
  private ViewPort buildSharedViewPort(int rotation) {
    try {
      if (binding != null
          && binding.viewFinder.getWidth() > 0
          && binding.viewFinder.getHeight() > 0) {
        ViewPort vp = binding.viewFinder.getViewPort(rotation);
        if (vp != null) {
          return vp;
        }
      }
    } catch (Throwable t) {
      Log.w(TAG, "buildSharedViewPort: PreviewView.getViewPort failed, using 4:3 fallback", t);
    }
    // Fallback: enforce the same 4:3 sensor ratio that all ResolutionSelectors request.
    return new ViewPort.Builder(new android.util.Rational(4, 3), rotation).build();
  }

  /**
   * Binds the given use cases through a {@link UseCaseGroup} that carries a shared {@link
   * ViewPort}, guaranteeing a common cropped field of view across Preview, ImageAnalysis and
   * ImageCapture.
   *
   * @param cameraSelector the camera selector to bind with
   * @param viewPort the shared ViewPort (may be {@code null} to skip view-port cropping)
   * @param useCases the use cases to bind (in order)
   * @return the bound {@link Camera}
   */
  private Camera bindUseCases(
      CameraSelector cameraSelector, @Nullable ViewPort viewPort, UseCase... useCases) {
    UseCaseGroup.Builder groupBuilder = new UseCaseGroup.Builder();
    if (viewPort != null) {
      groupBuilder.setViewPort(viewPort);
    }
    for (UseCase useCase : useCases) {
      groupBuilder.addUseCase(useCase);
    }
    return cameraProvider.bindToLifecycle(
        getViewLifecycleOwner(), cameraSelector, groupBuilder.build());
  }

  // --------- Capture ---------

  private void setPreviewSurfaceProviderWithLog(BindTier tier) {
    Preview.SurfaceProvider vfProvider = binding.viewFinder.getSurfaceProvider();
    preview.setSurfaceProvider(
        ContextCompat.getMainExecutor(requireContext()),
        request -> {
          android.util.Size s = request.getResolution();
          Log.i(
              TAG,
              "Preview SurfaceRequest: " + s.getWidth() + "x" + s.getHeight() + " tier=" + tier);
          vfProvider.onSurfaceRequested(request);
        });
  }

  private void logResolutions(String label) {
    String prev = "see SurfaceRequest log";
    String cap = "n/a";
    String ana = "n/a";

    if (imageCapture != null) {
      ResolutionInfo ri = imageCapture.getResolutionInfo();
      if (ri != null) {
        cap =
            ri.getResolution().getWidth()
                + "x"
                + ri.getResolution().getHeight()
                + " rot="
                + ri.getRotationDegrees()
                + "°";
      }
    }
    if (imageAnalysis != null) {
      ResolutionInfo ri2 = imageAnalysis.getResolutionInfo();
      if (ri2 != null) {
        ana =
            ri2.getResolution().getWidth()
                + "x"
                + ri2.getResolution().getHeight()
                + " rot="
                + ri2.getRotationDegrees()
                + "°";
      }
    }
    Log.i(
        TAG,
        "UseCase resolutions ["
            + label
            + "]: Preview="
            + prev
            + ", Capture="
            + cap
            + ", Analysis="
            + ana);
  }

  private float readPersistedZoomRatio() {
    Context ctx = getContext();
    if (ctx == null) return CameraZoomOptions.DEFAULT_ZOOM_RATIO;
    return ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE)
        .getFloat(PREF_CAMERA_ZOOM_RATIO, CameraZoomOptions.DEFAULT_ZOOM_RATIO);
  }

  private void persistZoomRatio(float ratio) {
    Context ctx = getContext();
    if (ctx == null) return;
    ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE)
        .edit()
        .putFloat(PREF_CAMERA_ZOOM_RATIO, ratio)
        .apply();
  }

  private void showZoomPicker() {
    if (camera == null) {
      UIUtils.showToast(requireContext(), R.string.camera_zoom_not_available, Toast.LENGTH_SHORT);
      return;
    }
    ZoomState state = camera.getCameraInfo().getZoomState().getValue();
    if (state == null || state.getMaxZoomRatio() <= state.getMinZoomRatio()) {
      UIUtils.showToast(requireContext(), R.string.camera_zoom_not_available, Toast.LENGTH_SHORT);
      return;
    }
    // Presets are expressed relative to the default back camera. When a dedicated lens is bound,
    // map its zoom range back into default-camera terms and fold in all lens factors so the user
    // can always switch back to 1x or up to an exposed telephoto lens.
    float effMin = state.getMinZoomRatio() * boundLensZoomFactor;
    float effMax = state.getMaxZoomRatio() * boundLensZoomFactor;
    if (backLenses != null) {
      for (CameraLensSelector.LensInfo lens : backLenses) {
        effMin = Math.min(effMin, lens.zoomFactor);
        effMax = Math.max(effMax, lens.zoomFactor);
      }
    }
    float[] ratios = CameraZoomOptions.buildPresetRatios(effMin, effMax);
    String[] labels = new String[ratios.length];
    for (int i = 0; i < ratios.length; i++) labels[i] = formatZoomRatio(ratios[i]);
    int checked = CameraZoomOptions.indexOfClosest(ratios, selectedZoomRatio);

    AlertDialog dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.camera_zoom_title)
            .setSingleChoiceItems(
                labels,
                checked,
                (d, which) -> {
                  selectedZoomRatio = ratios[which];
                  persistZoomRatio(selectedZoomRatio);
                  applySelectedZoomRatio();
                  d.dismiss();
                })
            .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
            .create();
    dialog.setOnShowListener(
        dlg -> {
          try {
            DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext());
          } catch (Throwable ignore) {
            // Dialog contrast improvement is best-effort
          }
          ListView listView = dialog.getListView();
          if (listView != null) {
            int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
            ViewGroup.LayoutParams params = listView.getLayoutParams();
            if (params != null && (params.height <= 0 || params.height > maxHeight)) {
              params.height = maxHeight;
              listView.setLayoutParams(params);
            }
          }
        });
    dialog.show();
  }

  /**
   * Resolves (and caches) the back lenses and returns the dedicated lens the current {@code
   * selectedZoomRatio} should use, or {@code null} for the default back camera.
   */
  private CameraLensSelector.LensInfo desiredLensForZoom() {
    if (backLenses == null && cameraProvider != null) {
      backLenses = CameraLensSelector.resolveBackLenses(cameraProvider);
      Log.w(TAG, "LensDiag: resolved back lenses=" + backLenses);
    }
    return CameraLensSelector.chooseLens(backLenses, selectedZoomRatio);
  }

  /**
   * Builds the camera selector for the current zoom selection: the default back camera, or an
   * explicit selector for a dedicated telephoto lens (issue #75). Also records the bound lens so
   * {@link #applySelectedZoomRatio()} can split the requested ratio into lens + digital zoom.
   */
  private CameraSelector buildCameraSelectorForZoom() {
    CameraLensSelector.LensInfo lens = desiredLensForZoom();
    if (lens == null) {
      boundLensCameraId = null;
      boundLensZoomFactor = 1.0f;
      return CameraSelector.DEFAULT_BACK_CAMERA;
    }
    boundLensCameraId = lens.cameraId;
    boundLensZoomFactor = lens.zoomFactor;
    Log.w(TAG, "LensDiag: binding dedicated lens " + lens + " for zoomRatio=" + selectedZoomRatio);
    return CameraLensSelector.buildSelector(lens.cameraId);
  }

  private void applySelectedZoomRatio() {
    if (camera == null) return;
    ZoomState state = camera.getCameraInfo().getZoomState().getValue();
    if (state == null) return;
    // If the selected ratio maps to a different dedicated lens, rebind with the new selector
    // (issue #75). bindWithTier() re-invokes this method once the lens is bound.
    CameraLensSelector.LensInfo desired = desiredLensForZoom();
    String desiredId = desired != null ? desired.cameraId : null;
    if (!java.util.Objects.equals(desiredId, boundLensCameraId)
        && cameraProvider != null
        && lastTier != null) {
      Log.w(
          TAG,
          "LensDiag: lens change required (bound="
              + boundLensCameraId
              + " -> desired="
              + desiredId
              + "), rebinding");
      bindWithTier(lastTier);
      return;
    }
    // Apply the remaining zoom digitally within the bound lens.
    float withinLens = selectedZoomRatio / boundLensZoomFactor;
    float normalized =
        CameraZoomOptions.normalize(withinLens, state.getMinZoomRatio(), state.getMaxZoomRatio());
    selectedZoomRatio = normalized * boundLensZoomFactor;
    Log.w(
        TAG,
        "LensDiag: applying zoomRatio="
            + normalized
            + " on lens id="
            + boundLensCameraId
            + " (factor="
            + boundLensZoomFactor
            + ", range="
            + state.getMinZoomRatio()
            + ".."
            + state.getMaxZoomRatio()
            + "), effectiveRatio="
            + selectedZoomRatio
            + ", activePhysicalId="
            + lastActivePhysicalCameraId
            + ", focalLength="
            + lastLensFocalLength);
    camera.getCameraControl().setZoomRatio(normalized);
    updateZoomButtonLabel(selectedZoomRatio);
    // On multi-camera devices a zoom change may switch the active physical camera; re-apply
    // the manual focus distance so it does not silently become stale.
    if (manualFocusSupported && currentManualFocusProgress() > 0) {
      applyManualFocus(currentManualFocusProgress(), true);
    }
  }

  private void updateZoomButtonLabel(float ratio) {
    if (binding == null) return;
    binding.buttonCameraZoom.setText(getString(R.string.camera_zoom_ratio, formatZoomRatio(ratio)));
  }

  private void setupTapToFocus() {
    if (binding == null) return;
    GestureDetector tapDetector =
        new GestureDetector(
            requireContext(),
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onSingleTapUp(@NonNull MotionEvent e) {
                return true;
              }
            });
    View.OnTouchListener listener =
        (view, event) -> {
          if (tapDetector.onTouchEvent(event)) {
            view.performClick();
            startTapToFocus(view, event.getX(), event.getY());
          }
          return true;
        };
    binding.viewFinder.setOnTouchListener(listener);
    binding.cornerOverlay.setOnTouchListener(listener);
  }

  private void startTapToFocus(View sourceView, float x, float y) {
    if (camera == null || binding == null || !isAdded()) return;
    try {
      int requestId = ++tapToFocusRequestId;
      float viewFinderX = x;
      float viewFinderY = y;
      if (sourceView != binding.viewFinder) {
        int[] sourceLocation = new int[2];
        int[] viewFinderLocation = new int[2];
        sourceView.getLocationOnScreen(sourceLocation);
        binding.viewFinder.getLocationOnScreen(viewFinderLocation);
        viewFinderX = sourceLocation[0] + x - viewFinderLocation[0];
        viewFinderY = sourceLocation[1] + y - viewFinderLocation[1];
      }
      viewFinderX = Math.max(0f, Math.min(binding.viewFinder.getWidth(), viewFinderX));
      viewFinderY = Math.max(0f, Math.min(binding.viewFinder.getHeight(), viewFinderY));
      if (manualFocusSupported && manualFocusActive) {
        Log.d(
            TAG,
            "Tap-to-focus resetting manual focus before AF, progress="
                + currentManualFocusProgress());
        manualFocusActive = false;
        updateManualFocusUi();
        persistManualFocusProgress(0);
        // Chain AF on the manual-focus reset request instead of a fixed delay: start the
        // metering only after the Camera2 interop options have actually been applied.
        ListenableFuture<Void> resetFuture = applyManualFocus(0, false);
        float chainedX = viewFinderX;
        float chainedY = viewFinderY;
        if (resetFuture != null) {
          resetFuture.addListener(
              () -> startTapToFocusMetering(requestId, chainedX, chainedY),
              ContextCompat.getMainExecutor(requireContext()));
        } else {
          startTapToFocusMetering(requestId, chainedX, chainedY);
        }
        return;
      }
      startTapToFocusMetering(requestId, viewFinderX, viewFinderY);
    } catch (Exception e) {
      Log.w(TAG, "Tap-to-focus could not be started", e);
    }
  }

  private void startTapToFocusMetering(int requestId, float x, float y) {
    if (camera == null || binding == null || !isAdded() || requestId != tapToFocusRequestId) return;
    MeteringPointFactory factory = binding.viewFinder.getMeteringPointFactory();
    MeteringPoint afPoint = factory.createPoint(x, y, TAP_TO_FOCUS_AF_POINT_SIZE);
    MeteringPoint aePoint = factory.createPoint(x, y, TAP_TO_FOCUS_AE_POINT_SIZE);
    FocusMeteringAction action =
        new FocusMeteringAction.Builder(afPoint, FocusMeteringAction.FLAG_AF)
            .addPoint(aePoint, FocusMeteringAction.FLAG_AE)
            // Auto-cancel restores continuous AF 5 s after the tap. This is safe again now
            // that CONTROL_AF_MODE is no longer forced via Camera2Interop: the tap really
            // focuses the tapped region, and afterwards the camera may resume scene-wide
            // continuous AF as expected.
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build();
    boolean meteringSupported = camera.getCameraInfo().isFocusMeteringSupported(action);
    Log.d(
        TAG,
        "Tap-to-focus start: x="
            + x
            + ", y="
            + y
            + ", preview="
            + binding.viewFinder.getWidth()
            + "x"
            + binding.viewFinder.getHeight()
            + ", manualFocusSupported="
            + manualFocusSupported
            + ", manualFocusProgress="
            + currentManualFocusProgress()
            + ", meteringFlags=AF+AE, afPointSize="
            + TAP_TO_FOCUS_AF_POINT_SIZE
            + ", aePointSize="
            + TAP_TO_FOCUS_AE_POINT_SIZE
            + ", supported="
            + meteringSupported);
    if (!meteringSupported) {
      Log.d(TAG, "Tap-to-focus not supported by camera for this point/action");
      UIUtils.showToast(requireContext(), R.string.tap_to_focus_not_supported, Toast.LENGTH_SHORT);
      return;
    }
    // Visual + haptic + spoken feedback instead of toasts: the indicator marks the exact
    // position of the focus request.
    binding.focusRing.showAt(x, y);
    HapticsUtils.vibrateOneShot(getContext(), 15L);
    announce(R.string.tap_to_focus_started);
    ListenableFuture<FocusMeteringResult> future =
        camera.getCameraControl().startFocusAndMetering(action);
    future.addListener(
        () -> {
          if (!isAdded()) return;
          try {
            if (requestId != tapToFocusRequestId) return;
            FocusMeteringResult result = future.get();
            boolean success = result.isFocusSuccessful();
            Log.d(TAG, "Tap-to-focus result: success=" + success);
            if (binding != null) binding.focusRing.onFocusResult(success);
            HapticsUtils.vibrateOneShot(getContext(), success ? 20L : 40L);
            announce(success ? R.string.tap_to_focus_done : R.string.tap_to_focus_not_locked);
          } catch (Exception e) {
            if (requestId != tapToFocusRequestId || isFocusMeteringCancellation(e)) {
              Log.d(TAG, "Tap-to-focus request was superseded", e);
              return;
            }
            Log.w(TAG, "Tap-to-focus failed", e);
            if (binding != null) binding.focusRing.onFocusResult(false);
            announce(R.string.tap_to_focus_failed);
          }
        },
        ContextCompat.getMainExecutor(requireContext()));
  }

  private boolean isFocusMeteringCancellation(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof androidx.camera.core.CameraControl.OperationCanceledException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  @OptIn(markerClass = ExperimentalCamera2Interop.class)
  private void setupManualFocusControl() {
    if (camera == null || binding == null || !isAdded()) return;
    Context ctx = getContext();
    if (ctx == null
        || !ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE)
            .getBoolean(CameraOptionsDialogFragment.BUNDLE_MANUAL_FOCUS, false)) {
      resetManualFocusControl();
      return;
    }
    manualFocusSupported = false;
    minimumFocusDistanceDiopters = 0f;
    try {
      Float minFocus =
          androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.getCameraInfo())
              .getCameraCharacteristic(
                  android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
      manualFocusSupported = minFocus != null && minFocus > 0f;
      minimumFocusDistanceDiopters = manualFocusSupported ? minFocus : 0f;
      // Diagnostics: uncalibrated devices report LENS_FOCUS_DISTANCE in arbitrary units, so
      // the slider is only a relative near/far control there. Logged for bug reports.
      Integer calibration =
          androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.getCameraInfo())
              .getCameraCharacteristic(
                  android.hardware.camera2.CameraCharacteristics
                      .LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
      String calibrationName =
          calibration == null
              ? "unknown"
              : switch (calibration) {
                case android.hardware.camera2.CameraCharacteristics
                    .LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED";
                case android.hardware.camera2.CameraCharacteristics
                    .LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE";
                case android.hardware.camera2.CameraCharacteristics
                    .LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "UNCALIBRATED";
                default -> String.valueOf(calibration);
              };
      Log.i(
          TAG,
          "Manual focus capability: minFocusDistance="
              + minFocus
              + " diopters, calibration="
              + calibrationName);
    } catch (Exception e) {
      Log.w(TAG, "Manual focus capability lookup failed", e);
    }
    if (!manualFocusSupported) {
      binding.focusControl.setVisibility(View.GONE);
      return;
    }
    binding.focusSlider.setValueFrom(FOCUS_SLIDER_MIN);
    binding.focusSlider.setValueTo(FOCUS_SLIDER_MAX);
    int savedProgress = readPersistedManualFocusProgress();
    manualFocusActive = savedProgress > 0;
    binding.focusSlider.setValue(
        manualFocusActive ? savedProgress : readPersistedManualFocusPosition());
    updateManualFocusUi();
    binding.focusSlider.setLabelFormatter(value -> String.valueOf((int) value));
    binding.focusSlider.addOnChangeListener(
        (slider, value, fromUser) -> {
          if (!fromUser) return;
          // Dragging the slider switches from AF to manual focus.
          manualFocusActive = true;
          updateManualFocusUi();
          // Live focus while dragging, throttled to one interop request per interval.
          pendingFocusProgress = (int) value;
          if (!focusUpdateScheduled) {
            focusUpdateScheduled = true;
            slider.postDelayed(
                () -> {
                  focusUpdateScheduled = false;
                  applyManualFocus(pendingFocusProgress, true);
                },
                MANUAL_FOCUS_LIVE_UPDATE_INTERVAL_MS);
          }
        });
    binding.focusSlider.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(@NonNull Slider slider) {
            // The previous gesture continues: do not announce "focus locked" in between.
            if (pendingFocusLockedToast != null) {
              slider.removeCallbacks(pendingFocusLockedToast);
              pendingFocusLockedToast = null;
            }
          }

          @Override
          public void onStopTrackingTouch(@NonNull Slider slider) {
            int progress = (int) slider.getValue();
            applyManualFocus(progress, true);
            persistManualFocusProgress(progress);
            persistManualFocusPosition(progress);
            // Show the confirmation toast only once the drag has really ended (no new
            // onStartTrackingTouch within the delay window).
            if (pendingFocusLockedToast != null) {
              slider.removeCallbacks(pendingFocusLockedToast);
            }
            pendingFocusLockedToast =
                () -> {
                  pendingFocusLockedToast = null;
                  if (isAdded() && progress > 0) {
                    UIUtils.showToast(
                        requireContext(), R.string.manual_focus_locked, Toast.LENGTH_SHORT);
                  }
                };
            slider.postDelayed(pendingFocusLockedToast, MANUAL_FOCUS_TOAST_DELAY_MS);
          }
        });
    binding.focusAfButton.setOnClickListener(
        v -> {
          if (!manualFocusActive) {
            // Second click toggles back to manual focus at the current slider position.
            manualFocusActive = true;
            updateManualFocusUi();
            int progress = (int) binding.focusSlider.getValue();
            persistManualFocusProgress(progress);
            applyManualFocus(progress, true);
            if (isAdded()) {
              UIUtils.showToast(requireContext(), R.string.manual_focus_locked, Toast.LENGTH_SHORT);
            }
            return;
          }
          manualFocusActive = false;
          updateManualFocusUi();
          persistManualFocusProgress(0);
          applyManualFocus(0, true);
          if (isAdded()) {
            UIUtils.showToast(requireContext(), R.string.autofocus_enabled, Toast.LENGTH_SHORT);
          }
        });
    binding.focusControl.setVisibility(View.VISIBLE);
    applyManualFocus(manualFocusActive ? savedProgress : 0, true);
  }

  /**
   * Returns the effective manual focus progress: the slider value while manual focus is active, or
   * {@code 0} when autofocus is selected (AF button checked).
   */
  private int currentManualFocusProgress() {
    if (!manualFocusActive || binding == null) return 0;
    return (int) binding.focusSlider.getValue();
  }

  private void updateManualFocusUi() {
    if (binding == null) return;
    binding.focusAfButton.setChecked(!manualFocusActive);
    updateManualFocusLabel(currentManualFocusProgress());
  }

  private int readPersistedManualFocusProgress() {
    Context ctx = getContext();
    if (ctx == null) return 0;
    int saved =
        ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE)
            .getInt(PREF_MANUAL_FOCUS_PROGRESS, 0);
    return Math.max(0, Math.min(FOCUS_SLIDER_MAX, saved));
  }

  private void persistManualFocusProgress(int progress) {
    Context ctx = getContext();
    if (ctx == null) return;
    ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE)
        .edit()
        .putInt(PREF_MANUAL_FOCUS_PROGRESS, Math.max(0, Math.min(FOCUS_SLIDER_MAX, progress)))
        .apply();
  }

  /**
   * Last manual-focus slider position (1..100), kept separately from the active progress so the
   * position is restored when the user switches back from AF to manual focus.
   */
  private int readPersistedManualFocusPosition() {
    Context ctx = getContext();
    if (ctx == null) return FOCUS_SLIDER_DEFAULT_POSITION;
    int saved =
        ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE)
            .getInt(PREF_MANUAL_FOCUS_POSITION, FOCUS_SLIDER_DEFAULT_POSITION);
    return Math.max(FOCUS_SLIDER_MIN, Math.min(FOCUS_SLIDER_MAX, saved));
  }

  private void persistManualFocusPosition(int position) {
    Context ctx = getContext();
    if (ctx == null) return;
    ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE)
        .edit()
        .putInt(
            PREF_MANUAL_FOCUS_POSITION,
            Math.max(FOCUS_SLIDER_MIN, Math.min(FOCUS_SLIDER_MAX, position)))
        .apply();
  }

  private void updateManualFocusLabel(int progress) {
    if (binding == null) return;
    binding.focusLabel.setText(
        progress <= 0
            ? getString(R.string.manual_focus_auto)
            : getString(R.string.manual_focus_label, progress));
  }

  /**
   * Applies the manual focus value via Camera2 interop.
   *
   * @param progress slider progress; {@code 0} restores continuous autofocus
   * @param liveUpdate {@code true} for throttled updates while dragging (suppresses the "focus
   *     locked" toast); slider release uses {@code true} as well, since the slider shows its own
   *     debounced confirmation toast in {@code onStopTrackingTouch}.
   * @return the future of the interop request, or {@code null} if nothing was applied
   */
  @OptIn(markerClass = ExperimentalCamera2Interop.class)
  private ListenableFuture<Void> applyManualFocus(int progress, boolean liveUpdate) {
    if (camera == null || !manualFocusSupported) return null;
    int clamped = Math.max(0, Math.min(FOCUS_SLIDER_MAX, progress));
    updateManualFocusLabel(clamped);
    androidx.camera.camera2.interop.CaptureRequestOptions.Builder options =
        new androidx.camera.camera2.interop.CaptureRequestOptions.Builder();
    if (clamped <= 0) {
      // Restore autofocus by clearing the interop overrides instead of forcing
      // CONTROL_AF_MODE=CONTINUOUS_PICTURE: a sticky interop AF mode would override CameraX's
      // 3A management and prevent tap-to-focus from switching to AF_MODE_AUTO.
      // setCaptureRequestOptions() replaces all previous options, so an empty bundle removes
      // both CONTROL_AF_MODE and LENS_FOCUS_DISTANCE.
    } else {
      float focusDistance = minimumFocusDistanceDiopters * clamped / FOCUS_SLIDER_MAX;
      options
          .setCaptureRequestOption(
              android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
              android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF)
          .setCaptureRequestOption(
              android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance);
      if (!liveUpdate && isAdded()) {
        UIUtils.showToast(requireContext(), R.string.manual_focus_locked, Toast.LENGTH_SHORT);
      }
    }
    return androidx.camera.camera2.interop.Camera2CameraControl.from(camera.getCameraControl())
        .setCaptureRequestOptions(options.build());
  }

  private void resetManualFocusControl() {
    manualFocusActive = false;
    if (binding != null) {
      binding.focusControl.setVisibility(View.GONE);
      updateManualFocusUi();
    }
    persistManualFocusProgress(0);
    applyManualFocus(0, true);
  }

  private String formatZoomRatio(float ratio) {
    DecimalFormat format =
        new DecimalFormat(ratio < 10f && ratio != Math.round(ratio) ? "0.#" : "0");
    return format.format(ratio);
  }

  private void attachWatchdogs() {
    // StreamState Log
    if (!streamObserverAttached) {
      binding
          .viewFinder
          .getPreviewStreamState()
          .observe(
              getViewLifecycleOwner(),
              (Observer<? super PreviewView.StreamState>)
                  state -> {
                    Log.d(TAG, "Preview stream state: " + state + " (tier=" + lastTier + ")");
                    // Accessibility: announce camera ready once when streaming starts
                    if (state == PreviewView.StreamState.STREAMING
                        && isAccessibilityModeEnabled()) {
                      long now = System.currentTimeMillis();
                      if (now - lastA11yReadyAnnounceTs > 4000L) {
                        lastA11yReadyAnnounceTs = now;
                        announce(R.string.a11y_camera_ready);
                        // Optional hint: volume keys act as shutter (announce once per session)
                        if (now - lastA11yVolumeHintTs > 60000L) { // once per minute/session window
                          lastA11yVolumeHintTs = now;
                          binding.viewFinder.postDelayed(
                              () -> announce(R.string.a11y_volume_shutter_hint), 1200);
                        }
                      }
                    }
                  });
      streamObserverAttached = true;
    }

    // After a short delay, check if STREAMING
    binding.viewFinder.postDelayed(
        () -> {
          if (!isAdded() || binding == null) return;
          PreviewView.StreamState st = binding.viewFinder.getPreviewStreamState().getValue();
          if (st != PreviewView.StreamState.STREAMING) {
            Log.w(TAG, "Watchdog: not STREAMING → escalate tier");
            escalateBindTier();
          }
        },
        2500);

    // CameraState errors → first try to rebind without Analysis
    camera
        .getCameraInfo()
        .getCameraState()
        .observe(
            getViewLifecycleOwner(),
            s -> {
              CameraState.StateError err = s.getError();
              if (err != null) {
                Log.w(TAG, "CameraState error: " + err.getCode());
                if (err.getCode() == CameraState.ERROR_STREAM_CONFIG) {
                  Log.w(
                      TAG,
                      "ERROR_STREAM_CONFIG → try rebind Preview+Capture (without Analysis) before tier escalate");
                  try {
                    cameraProvider.unbindAll();
                    camera =
                        cameraProvider.bindToLifecycle(
                            getViewLifecycleOwner(),
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture);
                    setPreviewSurfaceProviderWithLog(lastTier);
                    return; // Success → no tier escalation
                  } catch (Exception e) {
                    Log.w(TAG, "Rebind after ERROR_STREAM_CONFIG failed", e);
                  }
                  escalateBindTier();
                }
              }
            });
  }

  private void escalateBindTier() {
    if (cameraProvider == null) return;
    Log.w(
        TAG,
        "Escalate tier: current="
            + lastTier
            + ", streamState="
            + (binding != null ? binding.viewFinder.getPreviewStreamState().getValue() : null));
    if (lastTier == null || lastTier == BindTier.PERF) {
      bindWithTier(BindTier.COMPAT);
    } else if (lastTier == BindTier.COMPAT) {
      bindWithTier(BindTier.COMPAT_LOWRES);
    } else {
      Log.e(TAG, "Still not streaming after COMPAT_LOWRES.");
      if (isAdded())
        UIUtils.showToast(
            requireContext(), R.string.error_camera_preview_failed, Toast.LENGTH_LONG);
    }
  }

  private void captureImage() {
    if (!isAdded() || binding == null) {
      Log.d(TAG, "captureImage: not attached");
      return;
    }
    if (imageCapture == null) {
      Log.e(TAG, "captureImage: ImageCapture null");
      UIUtils.showToast(
          requireContext(), R.string.error_camera_not_initialized, Toast.LENGTH_SHORT);
      initializeCamera();
      return;
    }

    try {
      setProcessing(true);
      // Pause live analysis during capture to free CPU resources
      if (imageAnalysis != null) {
        imageAnalysis.clearAnalyzer();
      }
      binding.textCamera.setText(R.string.processing_image);

      // PATCH A: robust target directory (externalFilesDir can be null; SD card / vendor-specific
      // devices)
      File baseExt = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
      File outputDir;
      if (baseExt != null) {
        // /storage/.../Android/data/<pkg>/files/Pictures/MakeACopy (also on SD if mounted)
        outputDir = new File(baseExt, "MakeACopy");
        Log.d(TAG, "captureImage: using external files dir: " + outputDir.getAbsolutePath());
      } else {
        // Internal fallback: /data/data/<pkg>/files/Pictures/MakeACopy
        File picturesInInternal = new File(requireContext().getFilesDir(), "Pictures");
        //noinspection ResultOfMethodCallIgnored
        picturesInInternal.mkdirs();
        outputDir = new File(picturesInInternal, "MakeACopy");
        Log.w(
            TAG,
            "captureImage: external files dir null, using internal: "
                + outputDir.getAbsolutePath());
      }
      if (!outputDir.exists()) {
        boolean mkOk = outputDir.mkdirs();
        Log.d(TAG, "captureImage: ensure output directory exists -> " + mkOk);
      }

      // Create file with timestamp
      String timestamp =
          new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis());
      File photoFile = new File(outputDir, "MakeACopy_" + timestamp + ".jpg");
      Log.i(
          TAG,
          "captureImage: target file="
              + photoFile.getAbsolutePath()
              + ", rotationDeg="
              + toDegrees(getViewFinderRotation()));

      ImageCapture.OutputFileOptions outputOptions =
          new ImageCapture.OutputFileOptions.Builder(photoFile).build();

      // Pre-capture AF/AE/AWB lock for sharper, color-stable document shots.
      // AWB is included so that warm/cool ambient light (lamps, daylight mix) is locked in
      // before the shutter, improving OCR-relevant text/background contrast consistency.
      // Timeout 2s: balance between robust AF convergence and responsive capture; if AF
      // does not converge within the window, we still proceed (best-effort).
      if (camera != null && camera.getCameraControl() != null) {
        MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
        MeteringPoint center =
            mpf.createPoint(
                binding.viewFinder.getWidth() / 2f, binding.viewFinder.getHeight() / 2f);

        FocusMeteringAction fma =
            new FocusMeteringAction.Builder(
                    center,
                    FocusMeteringAction.FLAG_AF
                        | FocusMeteringAction.FLAG_AE
                        | FocusMeteringAction.FLAG_AWB)
                .setAutoCancelDuration(2, TimeUnit.SECONDS)
                .build();

        ListenableFuture<FocusMeteringResult> fut =
            camera.getCameraControl().startFocusAndMetering(fma);

        fut.addListener(
            () -> {
              try {
                FocusMeteringResult result =
                    fut.get(); // does not block; listener fires only after completion
                boolean ok = result != null && result.isFocusSuccessful();
                Log.d(TAG, "captureImage: pre-focus(AF/AE/AWB) result=" + ok);
              } catch (Exception e) {
                Log.w(TAG, "captureImage: pre-focus threw: " + e.getMessage());
              }
              doTakePicture(outputOptions, photoFile);
            },
            ContextCompat.getMainExecutor(requireContext()));
      } else {
        // Camera not yet bound (edge case): proceed without explicit pre-focus.
        Log.w(TAG, "captureImage: camera/cameraControl null, skipping pre-focus");
        doTakePicture(outputOptions, photoFile);
      }

    } catch (Exception e) {
      Log.e(TAG, "captureImage error: " + e.getMessage(), e);
      handleCaptureError(e);
    }
  }

  private void doTakePicture(ImageCapture.OutputFileOptions outputOptions, File photoFile) {
    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(requireContext()),
        new ImageCapture.OnImageSavedCallback() {
          @Override
          public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
            Log.d(
                TAG,
                "Image saved: " + photoFile.getAbsolutePath() + ", size=" + photoFile.length());
            // Confirm capture success with a short haptic tick (all users)
            HapticsUtils.vibrateOneShot(getContext(), 30L);
            // Accessibility: confirm capture success with haptic + spoken cue
            if (isAccessibilityModeEnabled() && binding != null && isAdded()) {
              binding.getRoot().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
              announce(R.string.a11y_capture_success);
            }
            Uri imageUri;
            try {
              imageUri =
                  FileProvider.getUriForFile(
                      requireContext(), BuildConfig.APPLICATION_ID + ".fileprovider", photoFile);
            } catch (IllegalArgumentException badRoot) {
              Log.w(TAG, "FileProvider root mismatch, fallback to file://", badRoot);
              imageUri = Uri.fromFile(photoFile);
            }

            if (cameraViewModel != null && isAdded()) {
              if (cropViewModel != null) {
                cropViewModel.setUserRotationDegrees(0);
                int captureDeg = toDegrees(getViewFinderRotation());
                cropViewModel.setCaptureRotationDegrees(captureDeg);
              }
              cameraViewModel.setImagePath(photoFile.getAbsolutePath());
              cameraViewModel.setImageUri(imageUri);
              // Live camera capture is not pre-cropped — disable the import-only fallback
              // heuristic.
              cameraViewModel.setImageSourceIsImported(false);

              OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
              ocrVm.resetForNewImage();

              cropViewModel.setImageCropped(false);
              cropViewModel.setImageBitmap(null);

              int dest = nextScanFlowDestination();
              try {
                Navigation.findNavController(requireView())
                    .navigate(dest, null, scanFlowNavOptions());
              } catch (IllegalArgumentException | IllegalStateException ignored) {
                // Best-effort; failure is non-critical
              }
            }
          }

          @Override
          public void onError(@NonNull ImageCaptureException exception) {
            Log.e(TAG, "Image capture failed: " + exception.getMessage(), exception);
            handleCaptureError(exception);
          }
        });
  }

  /**
   * Handles errors occurring during the image capture process by providing user feedback and
   * updating the UI to indicate the camera is ready for another action.
   *
   * @param exception The exception that was thrown during the image capture process.
   */
  private void handleCaptureError(Exception exception) {
    if (!isAdded() || binding == null) return;
    UIUtils.showToast(
        requireContext(),
        getString(R.string.error_image_capture_failed, exception.getMessage()),
        Toast.LENGTH_SHORT);
    binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
    setProcessing(false);
    // Re-enable live analysis after capture error
    boolean analysisPref =
        requireContext()
            .getSharedPreferences("export_options", Context.MODE_PRIVATE)
            .getBoolean("analysis_enabled", false);
    setLiveAnalysisEnabled(analysisPref);
    // Accessibility: speak failure
    if (isAccessibilityModeEnabled()) {
      announce(R.string.a11y_capture_failed);
    }
  }

  private NavOptions scanFlowNavOptions() {
    return new NavOptions.Builder()
        .setLaunchSingleTop(true)
        .setPopUpTo(R.id.navigation_camera, false)
        .build();
  }

  /**
   * Enables/disables action buttons during processing to prevent duplicate operations. Specifically
   * affects the Scan and Load (Pick Image) buttons if present.
   */
  private void setProcessing(boolean processing) {
    if (binding == null) return;
    binding.buttonScan.setEnabled(!processing);
    binding.buttonPickImage.setEnabled(!processing);
  }

  /**
   * Configures the UI to display the camera mode and ensures the interface is ready for capturing
   * an image. This method toggles visibility for specific UI elements and updates the displayed
   * text to guide the user for scanning a document. Additionally, it resets the state tracking for
   * low-light conditions to prepare for a fresh start in this mode.
   *
   * <p>Preconditions: - The `binding` property must not be null.
   *
   * <p>Behavior: - Shows the camera view (`viewFinder`) and hides the captured image preview
   * (`capturedImage`). - Updates visibility of buttons, enabling the scan-related buttons and
   * disabling others. - Adjusts visibility of `scanButtonContainer` if it exists in the binding. -
   * Sets the text to notify the user of the camera readiness for scanning. - Resets the
   * `lowLightPromptShown` variable to `false`.
   */
  private void showCameraMode() {
    if (binding == null) return;

    setProcessing(false);
    binding.viewFinder.setVisibility(View.VISIBLE);
    binding.capturedImage.setVisibility(View.GONE);
    binding.buttonContainer.setVisibility(View.GONE);
    binding.buttonScan.setVisibility(View.VISIBLE);
    binding.scanButtonContainer.setVisibility(View.VISIBLE);
    // Live corner preview: respect user preference
    boolean analysisPref = false;
    Context ctx = getContext();
    if (ctx != null) {
      android.content.SharedPreferences prefs =
          ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
      analysisPref = prefs.getBoolean("analysis_enabled", false); // Default OFF
    }

    // Important: always use the helper (overlay + analyzer + pref sync)
    setLiveAnalysisEnabled(analysisPref);
    binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);

    // Reset rotations for a new scan/page
    if (cropViewModel != null) {
      cropViewModel.setUserRotationDegrees(0);
      cropViewModel.setCaptureRotationDegrees(0);
    }

    lowLightPromptShown = false;
  }

  private void toggleFlashlight() {
    if (camera == null || !hasFlash || !isAdded()) {
      if (isAdded())
        UIUtils.showToast(requireContext(), R.string.flashlight_not_available, Toast.LENGTH_SHORT);
      return;
    }
    try {
      boolean newState = !isFlashlightOn;
      Log.i(
          TAG, "toggleFlashlight: " + (newState ? "ON" : "OFF") + " (was=" + isFlashlightOn + ")");
      isFlashlightOn = newState;
      camera.getCameraControl().enableTorch(isFlashlightOn);
      if (binding != null) {
        binding.buttonFlash.setImageResource(
            isFlashlightOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
        UIUtils.showToast(
            requireContext(),
            isFlashlightOn ? R.string.flashlight_on : R.string.flashlight_off,
            Toast.LENGTH_SHORT);
        // Accessibility feedback: speak state + light haptic
        if (isAccessibilityModeEnabled()) {
          binding.getRoot().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
          announce(isFlashlightOn ? R.string.flashlight_on : R.string.flashlight_off);
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "toggleFlashlight error: " + e.getMessage(), e);
      if (isAdded())
        UIUtils.showToast(
            requireContext(),
            getString(R.string.error_toggling_flashlight, e.getMessage()),
            Toast.LENGTH_SHORT);
    }
  }

  private void turnOffFlashlight() {
    if (camera != null && isFlashlightOn && isAdded()) {
      try {
        camera.getCameraControl().enableTorch(false);
        isFlashlightOn = false;
        if (binding != null) binding.buttonFlash.setImageResource(R.drawable.ic_flash_off);
      } catch (Exception e) {
        Log.e(TAG, "turnOffFlashlight error: " + e.getMessage(), e);
      }
    }
  }

  /**
   * Resets the camera to its initial state, preparing it for a new operation. This method handles
   * UI updates, frees up resources, and reinitializes the camera if necessary.
   *
   * <p>Steps performed in this method: 1. Ensures the binding is not null and the fragment is added
   * before proceeding. 2. Updates the UI by resetting the text and clearing the captured image. 3.
   * Turns off the flashlight to ensure it is disabled during the reset process. 4. Safely recycles
   * any existing bitmap from the captured image to free memory. 5. Switches the camera display to
   * its default mode. 6. Clears any stored image URI in the ViewModel. 7. Sets the low-light prompt
   * state to false. 8. Unbinds all previously bound use cases from the camera provider, if
   * applicable. 9. Reinitializes the camera only if the permission to access the camera is granted.
   * 10. Handles exceptions by logging the error and showing a user-friendly message.
   *
   * <p>Note: This method ensures that the camera and related resources are properly reset and
   * prepared to avoid resource leaks or inconsistent states during usage.
   */
  private void resetCamera() {
    if (binding == null || !isAdded()) return;

    binding.textCamera.setText(R.string.processing_image);

    // Turn off flashlight when resetting camera
    turnOffFlashlight();

    // Clean up bitmap safely (6)
    Drawable d = binding.capturedImage.getDrawable();
    binding.capturedImage.setImageDrawable(null);
    if (d instanceof BitmapDrawable) {
      Bitmap bm = ((BitmapDrawable) d).getBitmap();
      if (bm != null && !bm.isRecycled()) bm.recycle();
    }

    showCameraMode();

    if (cameraViewModel != null) {
      cameraViewModel.setImageUri(null);
      cameraViewModel.setImagePath(null);
    }

    lowLightPromptShown = false;

    try {
      if (cameraProvider != null) {
        cameraProvider.unbindAll();
        new Handler(Looper.getMainLooper())
            .post(
                () -> {
                  if (isAdded()
                      && cameraViewModel != null
                      && Boolean.TRUE.equals(
                          cameraViewModel.isCameraPermissionGranted().getValue())) {
                    initializeCamera();
                  }
                });
      } else {
        if (cameraViewModel != null
            && Boolean.TRUE.equals(cameraViewModel.isCameraPermissionGranted().getValue())) {
          initializeCamera();
        }
      }
    } catch (Exception e) {
      Log.e(TAG, "resetCamera error: " + e.getMessage());
      if (isAdded())
        UIUtils.showToast(
            requireContext(),
            getString(R.string.error_resetting_camera, e.getMessage()),
            Toast.LENGTH_SHORT);
    }
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    turnOffFlashlight();
    if (sensorManager != null && lightSensor != null) {
      sensorManager.unregisterListener(this);
    }
    if (cameraProvider != null) cameraProvider.unbindAll();
    if (orientationListener != null) orientationListener.disable();
    analysisEnabled = false;
    if (imageAnalysis != null) {
      imageAnalysis.clearAnalyzer();
      imageAnalysis = null;
    }
    if (analysisExecutor != null) {
      // Release pooled bitmaps and reusable Mats on the analyzer thread (where the
      // ThreadLocals were populated). Best-effort; failures are non-critical.
      try {
        analysisExecutor.submit(this::releaseLiveAnalysisResources).get(200, TimeUnit.MILLISECONDS);
      } catch (Throwable ignore) {
        // Best-effort
      }
      analysisExecutor.shutdownNow();
      analysisExecutor = null;
    }
    streamObserverAttached = false;
    binding = null;
  }

  /**
   * Releases per-thread bitmap pool slots and OpenCV Mats used by the live analyzer fast path.
   * Called from {@link #onDestroyView()} to avoid keeping native memory and Bitmaps around when the
   * camera UI is gone.
   */
  private void releaseLiveAnalysisResources() {
    Bitmap a = uprightBitmapPoolA.get();
    if (a != null && !a.isRecycled()) a.recycle();
    uprightBitmapPoolA.remove();
    Bitmap b = uprightBitmapPoolB.get();
    if (b != null && !b.isRecycled()) b.recycle();
    uprightBitmapPoolB.remove();
    org.opencv.core.Mat m;
    if ((m = nv21MatTL.get()) != null) {
      m.release();
      nv21MatTL.remove();
    }
    if ((m = rgbaMatTL.get()) != null) {
      m.release();
      rgbaMatTL.remove();
    }
    if ((m = resizedMatTL.get()) != null) {
      m.release();
      resizedMatTL.remove();
    }
    if ((m = rotatedMatTL.get()) != null) {
      m.release();
      rotatedMatTL.remove();
    }
  }

  /**
   * Initializes the light sensor for the application.
   *
   * <p>This method checks if the light sensor is available on the device and sets up the required
   * sensor references. It ensures that the light sensor manager is initialized properly if the
   * fragment is added to the activity. If the sensor is unavailable or there is an error during
   * initialization, appropriate logging messages are generated.
   *
   * <p>Key Operations: - Verifies if the fragment is currently added to an activity. - Retrieves
   * the sensor manager from the system context. - Checks for the availability of the light sensor.
   * - Updates the sensor's availability status and logs messages accordingly. - Handles exceptions
   * gracefully and logs errors.
   *
   * <p>Preconditions: - The fragment must be attached to the activity for the context to be valid.
   *
   * <p>Postconditions: - The `hasLightSensor` flag indicates whether the light sensor is available.
   * - The `lightSensor` field is populated if a light sensor is present. - Logs provide debugging
   * information regarding sensor availability and errors.
   */
  private void initLightSensor() {
    if (!isAdded()) return;

    sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
    if (sensorManager != null) {
      lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
      hasLightSensor = (lightSensor != null);
      if (hasLightSensor) {
        Log.i(
            TAG,
            "Light sensor available: name="
                + lightSensor.getName()
                + ", vendor="
                + lightSensor.getVendor()
                + ", maxRange="
                + lightSensor.getMaximumRange());
      } else {
        Log.i(TAG, "Light sensor not available");
      }
    }
  }

  /**
   * Shows a prompt when low-light conditions are detected, suggesting to enable the flashlight.
   *
   * <p>Debounces aggressively to avoid repeated dialogs: - Only if the fragment is added and
   * binding is available - Only if no other low-light dialog is visible - Only if
   * MIN_TIME_BETWEEN_PROMPTS has elapsed since the last prompt
   *
   * <p>On positive action, toggles the flashlight if available and currently off. Any exception
   * during dialog creation/show is caught and logged.
   */
  private void showLowLightPrompt() {
    if (!isAdded() || binding == null || isLowLightDialogVisible) return;
    long now = System.currentTimeMillis();
    if (lowLightPromptShown || (now - lastPromptTime) < MIN_TIME_BETWEEN_PROMPTS) return;

    // Accessibility Mode: speak hint instead of showing dialog
    if (isAccessibilityModeEnabled()) {
      if (now - lastA11yLowLightTs >= MIN_TIME_BETWEEN_PROMPTS) {
        lastA11yLowLightTs = now;
        announce(R.string.a11y_low_light_toggle_flash);
      }
      lowLightPromptShown = true;
      lastPromptTime = now;
      return;
    }

    try {
      isLowLightDialogVisible = true;
      AlertDialog dialog =
          new MaterialAlertDialogBuilder(requireContext())
              .setMessage(R.string.low_light_detected)
              .setPositiveButton(
                  R.string.flashlight_on,
                  new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                      if (!isFlashlightOn && hasFlash) toggleFlashlight();
                    }
                  })
              .setNegativeButton(
                  android.R.string.cancel, (dialogInterface, id) -> dialogInterface.dismiss())
              .create();

      dialog.setOnDismissListener(d -> isLowLightDialogVisible = false);
      dialog.setOnShowListener(
          dlg -> DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));
      dialog.show();

      lowLightPromptShown = true;
      lastPromptTime = now;
    } catch (Exception e) {
      Log.e(TAG, "showLowLightPrompt error: " + e.getMessage(), e);
      isLowLightDialogVisible = false;
    }
  }

  /**
   * Callback method that is triggered when there is a change in sensor data. Specifically, this
   * method listens for changes in the light sensor data and performs actions based on the detected
   * light level.
   *
   * @param event the SensorEvent containing details about the sensor data, such as the sensor type
   *     and its current values. In this case, the method focuses on events from the light sensor.
   */
  @Override
  public void onSensorChanged(SensorEvent event) {
    if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
      float lux = event.values[0];
      if (BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "Light level: " + lux + " lux");
      }
      if (lux < LOW_LIGHT_THRESHOLD
          && binding != null
          && binding.viewFinder.getVisibility() == View.VISIBLE
          && !isFlashlightOn
          && hasFlash) {
        showLowLightPrompt();
      }
    }
  }

  /**
   * Called when the accuracy of the registered sensor has changed.
   *
   * @param sensor The sensor for which the accuracy has changed.
   * @param accuracy The new accuracy of the sensor, represented as one of the predefined sensor
   *     accuracy constants.
   */
  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
    if (sensor.getType() == Sensor.TYPE_LIGHT) {
      if (BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "Light sensor accuracy: " + accuracy);
      }
    }
  }

  // ===== Live overlay & adaptive torch exposure =====

  // Verbose environment logging to help diagnose device-specific issues
  private void logEnvironment() {
    String versionName = BuildConfig.VERSION_NAME;
    int versionCode = BuildConfig.VERSION_CODE;
    String abis =
        Build.SUPPORTED_ABIS != null ? java.util.Arrays.toString(Build.SUPPORTED_ABIS) : "unknown";
    Locale loc = Locale.getDefault();
    boolean analysisPref = false;
    Context ctx = getContext();
    if (ctx != null) {
      android.content.SharedPreferences prefs =
          ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
      analysisPref = prefs.getBoolean("analysis_enabled", false);
    }
    String secPatch = Build.VERSION.SECURITY_PATCH;
    Log.i(
        TAG,
        "Env: app="
            + versionName
            + " ("
            + versionCode
            + ")"
            + ", sdk="
            + Build.VERSION.SDK_INT
            + " (release="
            + Build.VERSION.RELEASE
            + ", incremental="
            + Build.VERSION.INCREMENTAL
            + ", secPatch="
            + (secPatch != null ? secPatch : "-")
            + ")"
            + ", brand="
            + Build.BRAND
            + ", manuf="
            + Build.MANUFACTURER
            + ", model="
            + Build.MODEL
            + ", device="
            + Build.DEVICE
            + ", product="
            + Build.PRODUCT
            + ", hardware="
            + Build.HARDWARE
            + ", board="
            + Build.BOARD
            + ", fingerprint="
            + Build.FINGERPRINT
            + ", display="
            + Build.DISPLAY
            + ", abis="
            + abis
            + ", locale="
            + (loc != null ? loc.toLanguageTag() : "-")
            + ", analysisPref="
            + analysisPref);
  }

  // --------- Exposure Compensation ---------

  private void setupExposureCompensation() {
    if (camera == null || binding == null || !isAdded()) return;

    // Only show if the user enabled the feature in settings
    Context ctxEc = getContext();
    if (ctxEc != null) {
      android.content.SharedPreferences prefsEc =
          ctxEc.getSharedPreferences("export_options", Context.MODE_PRIVATE);
      if (!prefsEc.getBoolean(CameraOptionsDialogFragment.BUNDLE_EXPOSURE_COMPENSATION, false)) {
        binding.exposureControl.setVisibility(View.GONE);
        // Reset EV to 0 when feature is disabled
        applyExposureCompensation(0);
        persistExposureIndex(0);
        return;
      }
    }

    ExposureState es = camera.getCameraInfo().getExposureState();
    if (es == null || !es.isExposureCompensationSupported()) {
      binding.exposureControl.setVisibility(View.GONE);
      Log.i(TAG, "Exposure compensation not supported – hiding control");
      return;
    }

    android.util.Range<Integer> range = es.getExposureCompensationRange();
    float step =
        es.getExposureCompensationStep() != null
            ? es.getExposureCompensationStep().floatValue()
            : 0f;
    int lower = ExposureCompensationHelper.clampRangeLower(range.getLower(), step);
    int upper = ExposureCompensationHelper.clampRangeUpper(range.getUpper(), step);

    if (lower >= upper || step <= 0f) {
      binding.exposureControl.setVisibility(View.GONE);
      Log.i(
          TAG,
          "Exposure compensation range invalid ("
              + lower
              + ".."
              + upper
              + " step="
              + step
              + ") – hiding control");
      return;
    }

    Log.i(TAG, "Exposure compensation: range=" + lower + ".." + upper + " step=" + step);

    // Slider: 0..max maps to lower..upper
    int seekMax = upper - lower;
    binding.exposureSlider.setValueTo(seekMax);

    // Restore persisted index, clamped to current device range
    android.content.SharedPreferences prefs =
        requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
    int savedIndex = prefs.getInt(PREF_EXPOSURE_INDEX, 0);
    int clampedIndex = ExposureCompensationHelper.clampIndex(savedIndex, lower, upper);

    binding.exposureSlider.setValue(
        ExposureCompensationHelper.indexToProgress(clampedIndex, lower));
    updateExposureLabel(clampedIndex, step);
    applyExposureCompensation(clampedIndex);

    binding.exposureSlider.setLabelFormatter(
        value -> {
          int index = ExposureCompensationHelper.progressToIndex((int) value, lower);
          return ExposureCompensationHelper.formatEv(
              ExposureCompensationHelper.indexToEv(index, step));
        });
    binding.exposureSlider.addOnChangeListener(
        (slider, value, fromUser) -> {
          if (!fromUser) return;
          int index = ExposureCompensationHelper.progressToIndex((int) value, lower);
          updateExposureLabel(index, step);
        });
    binding.exposureSlider.addOnSliderTouchListener(
        new Slider.OnSliderTouchListener() {
          @Override
          public void onStartTrackingTouch(@NonNull Slider slider) {}

          @Override
          public void onStopTrackingTouch(@NonNull Slider slider) {
            int index = ExposureCompensationHelper.progressToIndex((int) slider.getValue(), lower);
            applyExposureCompensation(index);
            persistExposureIndex(index);
          }
        });

    binding.exposureControl.setVisibility(View.VISIBLE);
  }

  private void updateExposureLabel(int index, float step) {
    if (binding == null) return;
    float ev = ExposureCompensationHelper.indexToEv(index, step);
    String formatted = ExposureCompensationHelper.formatEv(ev);
    binding.exposureLabel.setText(getString(R.string.exposure_compensation_label, formatted));
  }

  private void applyExposureCompensation(int index) {
    if (camera == null) return;
    ExposureState es = camera.getCameraInfo().getExposureState();
    if (es == null || !es.isExposureCompensationSupported()) return;
    android.util.Range<Integer> range = es.getExposureCompensationRange();
    int clamped = ExposureCompensationHelper.clampIndex(index, range.getLower(), range.getUpper());
    Log.d(TAG, "Applying exposure compensation index=" + clamped);
    camera.getCameraControl().setExposureCompensationIndex(clamped);
  }

  private void persistExposureIndex(int index) {
    Context ctx = getContext();
    if (ctx == null) return;
    ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE)
        .edit()
        .putInt(PREF_EXPOSURE_INDEX, index)
        .apply();
  }

  private void logCameraCapabilities() {
    if (camera == null) {
      Log.i(TAG, "Capabilities: camera=null");
      return;
    }
    boolean flash = camera.getCameraInfo().hasFlashUnit();
    ExposureState es = camera.getCameraInfo().getExposureState();
    String ecInfo;
    if (es != null && es.isExposureCompensationSupported()) {
      android.util.Range<Integer> r = es.getExposureCompensationRange();
      float step =
          es.getExposureCompensationStep() != null
              ? es.getExposureCompensationStep().floatValue()
              : 0f;
      ecInfo =
          "EC supported idx=" + es.getExposureCompensationIndex() + " range=" + r + " step=" + step;
    } else {
      ecInfo = "EC not supported";
    }
    int rotDeg = toDegrees(getViewFinderRotation());
    Log.i(
        TAG,
        "Capabilities: flash="
            + flash
            + ", tier="
            + lastTier
            + ", rotation="
            + rotDeg
            + ", "
            + ecInfo
            + ", analysisEnabled="
            + analysisEnabled);
  }

  @androidx.annotation.VisibleForTesting
  ImageCapture getImageCaptureForTest() {
    return imageCapture;
  }

  /**
   * Tracks the active physical camera (logical multi-camera, API 29+) and the lens focal length
   * from a capture result, logging whenever the active lens changes (e.g. caused by zoom changes).
   * Diagnostics only; failures are swallowed and never break the capture stream.
   */
  private void trackLensFromCaptureResult(
      @NonNull android.hardware.camera2.TotalCaptureResult result) {
    try {
      String activeId = null;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        activeId =
            result.get(
                android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
      }
      Float focalLength = result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH);
      boolean idChanged = activeId != null && !activeId.equals(lastActivePhysicalCameraId);
      boolean flChanged =
          focalLength != null && Math.abs(focalLength - lastLensFocalLength) > 0.01f;
      if (idChanged || flChanged) {
        Log.w(
            TAG,
            "LensDiag: active lens changed: physicalId="
                + activeId
                + " (was "
                + lastActivePhysicalCameraId
                + "), focalLength="
                + focalLength
                + "mm (was "
                + lastLensFocalLength
                + "mm), zoomRatio="
                + selectedZoomRatio);
      }
      if (activeId != null) lastActivePhysicalCameraId = activeId;
      if (focalLength != null) lastLensFocalLength = focalLength;
    } catch (Throwable t) {
      // Diagnostics only; never break the capture stream
    }
  }

  /**
   * Logs detailed lens/camera diagnostics to help analyze whether the device exposes multiple
   * physical cameras (e.g. ultra-wide, wide, telephoto) to third-party apps and whether zoom
   * changes can switch the physical lens. Diagnostics only; failures are swallowed.
   */
  @OptIn(markerClass = ExperimentalCamera2Interop.class)
  private void logLensDiagnostics() {
    try {
      Context ctx = getContext();
      if (ctx == null) return;

      // Bound CameraX camera: id and zoom range
      if (camera != null) {
        String boundId =
            androidx.camera.camera2.interop.Camera2CameraInfo.from(camera.getCameraInfo())
                .getCameraId();
        ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
        Log.w(
            TAG,
            "LensDiag: bound cameraId="
                + boundId
                + (zs != null
                    ? ", zoomRange=" + zs.getMinZoomRatio() + ".." + zs.getMaxZoomRatio()
                    : ", zoomState=null"));
      }

      android.hardware.camera2.CameraManager cm =
          (android.hardware.camera2.CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
      if (cm == null) {
        Log.w(TAG, "LensDiag: CameraManager unavailable");
        return;
      }
      for (String id : cm.getCameraIdList()) {
        android.hardware.camera2.CameraCharacteristics cc = cm.getCameraCharacteristics(id);
        Integer facing = cc.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
        if (facing == null
            || facing != android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
          continue;
        }
        float[] focalLengths =
            cc.get(
                android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        android.util.SizeF sensorSize =
            cc.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Float maxDigitalZoom =
            cc.get(
                android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        String zoomRatioRange = "n/a";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
          android.util.Range<Float> zr =
              cc.get(android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
          if (zr != null) zoomRatioRange = zr.getLower() + ".." + zr.getUpper();
        }
        boolean logical = false;
        String physicalIds = "n/a";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          int[] caps =
              cc.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
          if (caps != null) {
            for (int cap : caps) {
              if (cap
                  == android.hardware.camera2.CameraCharacteristics
                      .REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                logical = true;
                break;
              }
            }
          }
          java.util.Set<String> phys = cc.getPhysicalCameraIds();
          if (!phys.isEmpty()) {
            physicalIds = phys.toString();
            // Log details of each physical sub-camera (focal length reveals the lens type)
            for (String pid : phys) {
              try {
                android.hardware.camera2.CameraCharacteristics pcc =
                    cm.getCameraCharacteristics(pid);
                float[] pFocal =
                    pcc.get(
                        android.hardware.camera2.CameraCharacteristics
                            .LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                android.util.SizeF pSensor =
                    pcc.get(
                        android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                Log.w(
                    TAG,
                    "LensDiag:   physical id="
                        + pid
                        + ", focalLengths="
                        + java.util.Arrays.toString(pFocal)
                        + ", sensorSize="
                        + pSensor);
              } catch (Throwable t) {
                Log.w(TAG, "LensDiag:   physical id=" + pid + " (characteristics unavailable)");
              }
            }
          }
        }
        Log.w(
            TAG,
            "LensDiag: back cameraId="
                + id
                + ", logicalMultiCamera="
                + logical
                + ", physicalIds="
                + physicalIds
                + ", focalLengths="
                + java.util.Arrays.toString(focalLengths)
                + ", sensorSize="
                + sensorSize
                + ", maxDigitalZoom="
                + maxDigitalZoom
                + ", zoomRatioRange="
                + zoomRatioRange);
      }
    } catch (Throwable t) {
      Log.w(TAG, "LensDiag: failed to collect lens diagnostics", t);
    }
  }

  private void setLiveAnalysisEnabled(boolean enabled) {
    if (!isAdded()) {
      analysisEnabled = enabled;
      return;
    }
    // Persist the user's explicit preference only
    analysisEnabled = enabled;
    android.content.SharedPreferences prefs =
        requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
    prefs.edit().putBoolean("analysis_enabled", enabled).apply();

    // Effective analysis: run analyzer whenever user enabled it, Accessibility Mode is on,
    // or the focus-quality indicator needs frames (independent of live corner detection).
    boolean a11y = isAccessibilityModeEnabled();
    boolean focusQuality = isFocusQualityIndicatorUserEnabled();
    boolean effectiveAnalysis = enabled || a11y || focusQuality;

    if (binding != null) {
      if (effectiveAnalysis && binding.viewFinder.getVisibility() == View.VISIBLE) {
        // Overlay visibility now follows the user's analysis preference only
        binding.cornerOverlay.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) {
          binding.cornerOverlay.setCorners(null);
          binding.cornerOverlay.setScore(null);
        }
      } else {
        binding.cornerOverlay.setCorners(null);
        binding.cornerOverlay.setVisibility(View.GONE);
        binding.cornerOverlay.setScore(null);
      }
      if (!focusQuality) {
        resetFocusQualityIndicator();
      }
    }
    if (imageAnalysis != null) {
      if (effectiveAnalysis) {
        ensureAnalysisExecutor();
        imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeFrameForCorners);
      } else {
        imageAnalysis.clearAnalyzer();
        if (analysisExecutor != null) {
          analysisExecutor.shutdownNow();
          analysisExecutor = null;
        }
      }
    }
  }

  // NEU: strikt 1280x960 / YUV und 4:3
  @OptIn(markerClass = ExperimentalCamera2Interop.class)
  private void setupOrUpdateImageAnalysis(int rotation) {
    ResolutionSelector analysisRs =
        new ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                new AspectRatioStrategy(
                    AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .setResolutionStrategy(
                new ResolutionStrategy(
                    new android.util.Size(1280, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build();

    if (imageAnalysis == null) {
      ImageAnalysis.Builder analysisBuilder =
          new ImageAnalysis.Builder()
              .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
              .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
              .setTargetRotation(rotation)
              .setResolutionSelector(analysisRs);

      // Add Camera2 interop to read focus distance from each frame
      Camera2Interop.Extender<ImageAnalysis> analysisExt =
          new Camera2Interop.Extender<>(analysisBuilder);
      analysisExt.setSessionCaptureCallback(
          new android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(
                @NonNull android.hardware.camera2.CameraCaptureSession session,
                @NonNull android.hardware.camera2.CaptureRequest request,
                @NonNull android.hardware.camera2.TotalCaptureResult result) {
              // Read focus distance (in diopters = 1/meters)
              Float focusDist =
                  result.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE);
              if (focusDist != null) {
                lastFocusDistanceDiopters = focusDist;
              }
              // Lens diagnostics: track the active physical camera (logical multi-camera) and
              // the focal length so lens switches caused by zoom changes become visible in logs.
              trackLensFromCaptureResult(result);
            }
          });

      imageAnalysis = analysisBuilder.build();
    } else {
      imageAnalysis.setTargetRotation(rotation);
    }

    // Analyzer should run when the user enabled visual preview, Accessibility Mode is on,
    // or the focus-quality indicator needs frames (independent of live corner detection).
    boolean a11y = isAccessibilityModeEnabled();
    boolean focusQuality = isFocusQualityIndicatorUserEnabled();
    boolean effectiveAnalysis = analysisEnabled || a11y || focusQuality;

    if (effectiveAnalysis) {
      ensureAnalysisExecutor();
      imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeFrameForCorners);
      if (binding != null) {
        // Overlay visibility now depends solely on the user's analysis preference
        binding.cornerOverlay.setVisibility(analysisEnabled ? View.VISIBLE : View.GONE);
        if (!analysisEnabled) {
          binding.cornerOverlay.setCorners(null);
          binding.cornerOverlay.setScore(null);
        }
      }
      if (!focusQuality) {
        resetFocusQualityIndicator();
      }
      // Initialize Accessibility Guidance controller when active and flagged
      if (FeatureFlags.isA11yGuidanceEnabled() && a11y) {
        initA11yGuidanceController();
      } else {
        clearA11yGuidanceController();
      }
    } else {
      imageAnalysis.clearAnalyzer();
      if (binding != null) {
        binding.cornerOverlay.setCorners(null);
        binding.cornerOverlay.setVisibility(View.GONE);
        binding.cornerOverlay.setScore(null);
      }
      resetFocusQualityIndicator();
      clearA11yGuidanceController();
    }
  }

  private void ensureAnalysisExecutor() {
    if (analysisExecutor == null || analysisExecutor.isShutdown()) {
      analysisExecutor =
          java.util.concurrent.Executors.newSingleThreadExecutor(
              r -> {
                Thread t = new Thread(r, "CornerAnalysis");
                t.setDaemon(true);
                return t;
              });
    }
  }

  private void initA11yGuidanceController() {
    if (a11yGuidanceController == null) {
      a11yGuidanceController = new AccessibilityGuidanceController(1200, 2, 6000);
    } else {
      a11yGuidanceController.reset();
    }
    // Initialize new state machine (concept-based implementation)
    if (a11yStateMachine == null) {
      a11yStateMachine = new A11yStateMachine();
    } else {
      a11yStateMachine.reset();
    }
    // Reset last event for overlay cadence
    lastGuidanceEventHint = null;
  }

  private void clearA11yGuidanceController() {
    a11yGuidanceController = null;
    a11yStateMachine = null;
    lastGuidanceEventHint = null;
  }

  /**
   * Computes the Laplacian-variance sharpness score for the current analysis frame and updates the
   * segment indicator. Runs on the analyzer thread, only on frames that already passed the existing
   * analysis throttle, and only while the feature flag is enabled.
   *
   * <p>ROI priority per design note: bounding rect of the detected quad, else full frame. UI is
   * only touched when the mapped segment count actually changes (no per-frame invalidations).
   */
  private void measureFocusQuality(
      @NonNull Bitmap bmp, @androidx.annotation.Nullable org.opencv.core.Point[] quad, long nowMs) {
    try {
      if (focusQualityAnalyzer == null) focusQualityAnalyzer = new FocusQualityAnalyzer();
      org.opencv.core.Rect roi = null;
      if (quad != null && quad.length == 4) {
        double minX = quad[0].x, minY = quad[0].y, maxX = quad[0].x, maxY = quad[0].y;
        for (int i = 1; i < 4; i++) {
          minX = Math.min(minX, quad[i].x);
          minY = Math.min(minY, quad[i].y);
          maxX = Math.max(maxX, quad[i].x);
          maxY = Math.max(maxY, quad[i].y);
        }
        roi =
            new org.opencv.core.Rect(
                (int) Math.floor(minX),
                (int) Math.floor(minY),
                (int) Math.ceil(maxX - minX),
                (int) Math.ceil(maxY - minY));
      }
      long t0 = SystemClock.elapsedRealtime();
      double rawVariance = focusQualityAnalyzer.measure(bmp, roi);
      long durationMs = SystemClock.elapsedRealtime() - t0;
      if (rawVariance < 0) return; // measurement failed; keep last state
      double score = focusQualityMeter.update(rawVariance, nowMs);
      int segments = FocusQualityMeter.segmentsForScore(score);
      if (BuildConfig.DEBUG) {
        Log.d(
            TAG,
            "[FOCUS_Q] raw="
                + String.format(Locale.US, "%.1f", rawVariance)
                + ", score="
                + String.format(Locale.US, "%.3f", score)
                + ", segments="
                + segments
                + ", roi="
                + (roi != null
                    ? roi.width + "x" + roi.height
                    : "full " + bmp.getWidth() + "x" + bmp.getHeight())
                + ", durationMs="
                + durationMs);
      }
      if (segments == lastFocusQualitySegments) return; // no UI churn for unchanged level
      lastFocusQualitySegments = segments;
      final FocusQualityMeter.Band band = FocusQualityMeter.bandForSegments(segments);
      runOnUiThreadSafe(
          () -> {
            if (binding == null) return;
            // Re-check on the UI thread: the user may have disabled the indicator (camera
            // options) while this frame was still in flight on the analyzer thread. Without
            // this guard a stale post could re-show the indicator right after
            // resetFocusQualityIndicator() hid it.
            if (!isFocusQualityIndicatorUserEnabled()) return;
            if (binding.focusQualityIndicator.getVisibility() != View.VISIBLE) {
              binding.focusQualityIndicator.setVisibility(View.VISIBLE);
            }
            binding.focusQualityIndicator.setLevel(segments);
            maybeAnnounceFocusQuality(band);
          });
    } catch (Throwable t) {
      Log.w(TAG, "Focus-quality measurement failed: " + t.getMessage());
    }
  }

  /**
   * Announces the coarse focus-quality band via the existing announce helper. Rate-limited (same
   * cadence as the framing signal) and only emitted on band changes to avoid TalkBack spam.
   */
  private void maybeAnnounceFocusQuality(FocusQualityMeter.Band band) {
    if (band == lastAnnouncedFocusQualityBand) return;
    long now = System.currentTimeMillis();
    if (now - lastFocusQualityAnnounceTs < FOCUS_QUALITY_ANNOUNCE_MIN_INTERVAL_MS) return;
    lastAnnouncedFocusQualityBand = band;
    lastFocusQualityAnnounceTs = now;
    if (!isAccessibilityModeEnabled()) return; // visual users see the segments
    int resId;
    switch (band) {
      case EXCELLENT:
        resId = R.string.focus_quality_excellent;
        break;
      case GOOD:
        resId = R.string.focus_quality_good;
        break;
      case LOW:
      default:
        resId = R.string.focus_quality_low;
        break;
    }
    announce(resId);
  }

  /**
   * Returns whether the live focus-quality indicator should run: the build-time feature flag must
   * be enabled AND the user must have turned the setting on in the camera options dialog. The
   * setting is independent of the live corner detection preference.
   */
  private boolean isFocusQualityIndicatorUserEnabled() {
    if (!FeatureFlags.isFocusQualityIndicatorEnabled()) return false;
    Context ctx = getContext();
    if (ctx == null) return false;
    return ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE)
        .getBoolean(CameraOptionsDialogFragment.BUNDLE_FOCUS_QUALITY_INDICATOR, false);
  }

  /** Hides the focus-quality indicator and resets the normalization state. */
  private void resetFocusQualityIndicator() {
    focusQualityMeter.reset();
    lastFocusQualitySegments = -1;
    lastAnnouncedFocusQualityBand = null;
    if (binding != null) {
      binding.focusQualityIndicator.setVisibility(View.GONE);
      binding.focusQualityIndicator.setLevel(0);
    }
  }

  private void analyzeFrameForCorners(@NonNull ImageProxy image) {
    Bitmap bmp = null;
    try {
      // Note: Do NOT adapt exposure during live corner preview.
      // Running adaptive EC per analyzed frame caused progressive darkening when the torch is on.
      // We keep exposure stable here to ensure a consistent preview brightness.

      // Run analyzer if user enabled visual analysis, A11y mode is active (internal analysis),
      // or the focus-quality indicator is enabled (sharpness measurement only).
      boolean wantCorners = analysisEnabled || isAccessibilityModeEnabled();
      boolean wantFocusQuality = isFocusQualityIndicatorUserEnabled();
      if ((!wantCorners && !wantFocusQuality) || binding == null || !isAdded()) return;

      long now = System.currentTimeMillis();
      if (now - lastAnalysisTs < 180) return; // ~5–6 FPS
      lastAnalysisTs = now;

      // Convert to small upright bitmap (to reduce CPU)
      // CameraX supplies rotationDegrees such that rotating the buffer by this angle
      // aligns it with the current targetRotation (we update that via OrientationEventListener).
      // This means our analysis runs in a display‑aligned, "upright" frame of reference.
      // Consequently, directional guidance (left/right/up/down) refers to the physical
      // sides of the phone as currently held.
      int imgRotDeg = image.getImageInfo().getRotationDegrees();
      bmp =
          yuvToBitmapUprightSmall(
              image,
              OpenCVUtils
                  .DETECTION_MAX_EDGE); // use central constant for consistent corner detection
      if (BuildConfig.DEBUG) {
        int dispRot = getViewFinderRotation();
        Log.d(
            TAG,
            "[A11Y_DIR] displayRot="
                + dispRot
                + ", imgRotDeg="
                + imgRotDeg
                + ", a11y="
                + isAccessibilityModeEnabled());
      }
      if (bmp == null) return;

      // Capture bitmap dimensions for any deferred/lambda usage.
      // (bmp itself is not effectively-final because we recycle it in finally.)
      final int bmpW = bmp.getWidth();
      final int bmpH = bmp.getHeight();

      ensureOpenCvInitialized();

      // Skip corner detection entirely when only the focus-quality indicator needs frames:
      // the sharpness measurement then simply uses the full frame as ROI.
      // Live-DocQuad liefert aktuell keinen Score (Determinismus/Performance).
      final org.opencv.core.Point[] pts = wantCorners ? detectLiveCorners(bmp) : null;
      final boolean hasValid = pts != null;

      // Map bitmap coords to overlay coords (PreviewView with FIT_CENTER) when valid.
      // The detection bitmap is built from the FULL analysis buffer, but the PreviewView (sharing
      // a common ViewPort with Preview/Capture) only displays the cropped field of view. Compute
      // that crop region in upright-bitmap space so the overlay lines up with what is shown.
      final android.graphics.RectF analysisCropUpright =
          computeUprightCropRect(image, OpenCVUtils.DETECTION_MAX_EDGE, bmpW, bmpH);
      android.graphics.PointF[] viewPts =
          hasValid ? mapToOverlayPoints(pts, bmpW, bmpH, analysisCropUpright) : null;

      updateFocusQuality(wantFocusQuality, bmp, pts, now);

      // Optional: Evaluate FramingEngine (logging and/or accessibility guidance)
      boolean wantFraming =
          FeatureFlags.isFramingLoggingEnabled()
              || (FeatureFlags.isA11yGuidanceEnabled() && isAccessibilityModeEnabled());
      final FramingEval framing =
          wantFraming ? evaluateFraming(bmp, pts, bmpW, bmpH, now) : FramingEval.NONE;

      // Removed: direct one‑shot announcement for orientation. Orientation now flows into the
      // central guidance path (see evaluateFraming), including hysteresis/rate limiting.

      smoothOverlayCorners(viewPts);

      // Use FramingEngine quality (0..1) instead of det.score() which is always 0.0 for DocQuad
      double rawScore = (framing.result() != null) ? framing.result().quality : 0.0;
      // Compute score EMA only when a value exists
      if (hasValid) updateScoreEma(rawScore);

      // UI update: overlay and score with hysteresis
      runOnUiThreadSafe(
          () -> {
            if (binding == null) return;
            renderCornerOverlay(hasValid);
            renderFramingDevOverlay(framing, bmpW, bmpH);
          });

      updateGoodFramingFeedback(rawScore);
    } catch (Exception e) {
      Log.w(TAG, "analyzeFrameForCorners failed: " + e.getMessage(), e);
    } finally {
      // Avoid per-frame bitmap accumulation / GC pressure in live analysis.
      // The fast OpenCV path returns a pooled bitmap that must NOT be recycled here
      // (it is reused on subsequent frames). Only recycle bitmaps that are NOT in the pool
      // (i.e. produced by the legacy fallback path).
      try {
        if (bmp != null && !bmp.isRecycled() && !isPooledBitmap(bmp)) bmp.recycle();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      image.close();
    }
  }

  /** Init CV once. */
  private void ensureOpenCvInitialized() {
    if (OpenCVUtils.isInitialized()) return;
    try {
      OpenCVUtils.init(requireContext().getApplicationContext());
    } catch (Exception e) {
      Log.w(TAG, "OpenCV init failed", e);
    }
  }

  /**
   * Live focus-quality (sharpness) measurement — user-toggleable in the camera options. Reuses the
   * already throttled analysis pass and the small upright bitmap: no extra pipeline, no copies.
   */
  private void updateFocusQuality(
      boolean wantFocusQuality, Bitmap bmp, @Nullable org.opencv.core.Point[] pts, long now) {
    if (wantFocusQuality) {
      measureFocusQuality(bmp, pts, now);
    } else if (lastFocusQualitySegments != -1) {
      // Self-healing: the indicator was disabled (e.g. via camera options) while corner
      // detection keeps the analyzer running — make sure stale indicator UI is hidden.
      lastFocusQualitySegments = -1;
      runOnUiThreadSafe(this::resetFocusQualityIndicator);
    }
  }

  private void updateScoreEma(double rawScore) {
    if (lastScoreEma < 0) lastScoreEma = rawScore;
    else lastScoreEma = SCORE_EMA_ALPHA * rawScore + (1.0 - SCORE_EMA_ALPHA) * lastScoreEma;
  }

  /** Accessibility feedback when framing is stable and good. */
  private void updateGoodFramingFeedback(double rawScore) {
    if (!isAccessibilityModeEnabled()) {
      // Reset counters when mode is off
      lastScore = 0.0;
      stableCount = 0;
      return;
    }
    // Use the smoothed score for stability logic when available
    lastScore = (lastScoreEma >= 0 ? lastScoreEma : rawScore);
    if (isStableFor(5, 0.8)) {
      maybeSignalGoodFraming();
    }
  }

  /**
   * Runs the live corner detector. DocQuad is the standard detector with OpenCV as fallback; the
   * detector is cached so DocQuad runner/throttle actually persist across frames.
   *
   * @return the four corners (TL, TR, BR, BL) in bitmap coordinates, or {@code null} if no document
   *     was found
   */
  @Nullable
  private org.opencv.core.Point[] detectLiveCorners(Bitmap bmp) {
    de.schliweb.makeacopy.ml.corners.CornerDetector liveDetector = cachedLiveCornerDetector;
    if (liveDetector == null || !cachedLiveCornerDetectorFlag) {
      liveDetector =
          de.schliweb.makeacopy.ml.corners.CornerDetectorFactory.forLive(
              requireContext(), docQuadOrtRunner);
      cachedLiveCornerDetector = liveDetector;
      cachedLiveCornerDetectorFlag = true;
    }

    de.schliweb.makeacopy.ml.corners.DetectionResult r = liveDetector.detect(bmp, requireContext());
    if (r == null
        || !r.success
        || r.cornersOriginalTLTRBRBL == null
        || r.cornersOriginalTLTRBRBL.length != 4) {
      return null;
    }
    org.opencv.core.Point[] pts = new org.opencv.core.Point[4];
    for (int i = 0; i < 4; i++) {
      pts[i] =
          new org.opencv.core.Point(
              r.cornersOriginalTLTRBRBL[i][0], r.cornersOriginalTLTRBRBL[i][1]);
    }
    return pts;
  }

  @Nullable
  private static android.graphics.PointF[] toPointFs(@Nullable org.opencv.core.Point[] pts) {
    if (pts == null || pts.length != 4) return null;
    android.graphics.PointF[] quad = new android.graphics.PointF[4];
    for (int i = 0; i < 4; i++) {
      quad[i] = new android.graphics.PointF((float) pts[i].x, (float) pts[i].y);
    }
    return quad;
  }

  /**
   * FramingEngine evaluation of one analyzed frame.
   *
   * @param result the framing result, or {@code null} if framing was not evaluated or failed
   * @param fallbackRect the model rect the framing was evaluated against (dev overlay)
   * @param orientBucket estimated text orientation, 0 or 90; -1 if unknown
   * @param orientConf confidence of the orientation estimate; -1 if unknown
   */
  private record FramingEval(
      @Nullable FramingResult result,
      @Nullable android.graphics.RectF fallbackRect,
      int orientBucket,
      double orientConf) {
    static final FramingEval NONE = new FramingEval(null, null, -1, -1.0);
  }

  /** Evaluates the FramingEngine for this frame and feeds the accessibility guidance. */
  private FramingEval evaluateFraming(
      Bitmap bmp, @Nullable org.opencv.core.Point[] pts, int bmpW, int bmpH, long now) {
    FramingResult fr;
    android.graphics.RectF fbRect;
    try {
      fbRect = OpenCVUtils.getFallbackRectF(bmpW, bmpH);
      fr =
          new FramingEngine().evaluate(new FramingEngine.Input(bmpW, bmpH, toPointFs(pts), fbRect));
      if (FeatureFlags.isFramingLoggingEnabled()) {
        Log.d("Framing", "FramingResult=" + fr);
      }
    } catch (Exception ignored) {
      return FramingEval.NONE;
    }
    // Estimate orientation once (shared for A11y & overlay)
    int orientBucket = -1; // 0 or 90
    double orientConf = -1.0;
    try {
      OpenCVUtils.OrientationEstimate est = OpenCVUtils.estimateTextOrientation(bmp);
      orientBucket = (est.bucketDeg() == 90) ? 90 : 0;
      orientConf = est.confidence();
    } catch (Exception e) {
      Log.d(TAG, "estimateTextOrientation failed", e);
    }
    try {
      feedA11yGuidance(pts, bmpW, bmpH, fr, orientBucket, orientConf, now);
    } catch (Exception ignored) {
      // Best-effort; failure is non-critical
    }
    return new FramingEval(fr, fbRect, orientBucket, orientConf);
  }

  /** A11y guidance using the state machine (concept-based implementation). */
  private void feedA11yGuidance(
      @Nullable org.opencv.core.Point[] pts,
      int bmpW,
      int bmpH,
      @Nullable FramingResult fr,
      int orientBucket,
      double orientConf,
      long now) {
    // Ensure state machine exists so overlay can mirror cadence even without a screen reader
    if (a11yStateMachine == null) {
      initA11yGuidanceController();
    }
    if (a11yStateMachine == null) return;

    // Process frame through state machine (with model-free focus distance)
    a11yStateMachine.onFrame(
        toPointFs(pts),
        bmpW,
        bmpH,
        withOrientationHint(fr, orientBucket, orientConf),
        lastFocusDistanceDiopters, // model-free distance signal
        now,
        (event, state) -> onA11yGuidanceEvent(event, state, now));
  }

  /** Optional: inject an orientation hint into the FramingResult if there is no document. */
  @VisibleForTesting
  @Nullable
  static FramingResult withOrientationHint(
      @Nullable FramingResult fr, int orientBucket, double orientConf) {
    if (fr == null || fr.hasDocument || orientConf < 0.30) return fr;
    GuidanceHint oriHint =
        (orientBucket == 90)
            ? GuidanceHint.ORIENTATION_LANDSCAPE_TIP
            : GuidanceHint.ORIENTATION_PORTRAIT_TIP;
    return new FramingResult(
        fr.quality,
        fr.dxNorm,
        fr.dyNorm,
        fr.scaleRatio,
        fr.tiltHorizontal,
        fr.tiltVertical,
        oriHint,
        false);
  }

  private void onA11yGuidanceEvent(
      A11yStateMachine.Event event, A11yStateMachine.State state, long now) {
    // Map event to GuidanceHint for compatibility
    GuidanceHint hint = mapEventToHint(event);
    if (hint == null) return;

    // Update shared cadence state for overlay
    lastGuidanceEventHint = hint;

    // Optional: emit debug log for QA when framing logging is enabled
    if (BuildConfig.FEATURE_FRAMING_LOGGING) {
      A11yStateMachine.DebugInfo dbg = a11yStateMachine.getDebugInfo();
      Log.d(
          TAG,
          "[A11Y_STATE] event="
              + event
              + ", state="
              + state
              + ", hint="
              + hint
              + ", ts="
              + now
              + ", debug="
              + dbg);
    }

    // Optional micro haptic for central hints (A11y mode only)
    maybeVibrateForHint(hint);

    // Only announce via TTS when accessibility mode is enabled
    if (isAccessibilityModeEnabled()) {
      int resId = mapHintToRes(hint);
      if (resId != 0) {
        runOnUiThreadSafe(() -> announce(resId));
      }
    }
  }

  /**
   * Jitter reduction & hysteresis for the corner preview: counts consecutive valid/invalid frames
   * and applies an EMA to the overlay corners.
   *
   * @param viewPts corners in overlay coordinates, or {@code null} if this frame has no valid quad
   */
  private void smoothOverlayCorners(@Nullable android.graphics.PointF[] viewPts) {
    if (viewPts == null) {
      consecutiveInvalidFrames++;
      consecutiveValidFrames = 0;
      return;
    }
    consecutiveValidFrames++;
    consecutiveInvalidFrames = 0;
    if (lastFilteredCorners == null) {
      // Init filter
      lastFilteredCorners = new android.graphics.PointF[4];
      for (int i = 0; i < 4; i++) {
        lastFilteredCorners[i] = new android.graphics.PointF(viewPts[i].x, viewPts[i].y);
      }
      return;
    }
    // Apply EMA to each coordinate
    for (int i = 0; i < 4; i++) {
      android.graphics.PointF f = lastFilteredCorners[i];
      f.x = CORNER_EMA_ALPHA * viewPts[i].x + (1f - CORNER_EMA_ALPHA) * f.x;
      f.y = CORNER_EMA_ALPHA * viewPts[i].y + (1f - CORNER_EMA_ALPHA) * f.y;
    }
  }

  /** UI thread: shows the smoothed corner preview with show/hide hysteresis. */
  private void renderCornerOverlay(boolean hasValid) {
    // Show visible corner preview only when the user enabled visual analysis
    if (!analysisEnabled) {
      binding.cornerOverlay.setCorners(null);
      return;
    }
    boolean shouldShow =
        (consecutiveValidFrames >= OVERLAY_SHOW_AFTER_VALID)
            || binding.cornerOverlay.getVisibility() == View.VISIBLE;
    if (hasValid && lastFilteredCorners != null && shouldShow) {
      binding.cornerOverlay.setCorners(lastFilteredCorners);
    } else if (consecutiveInvalidFrames >= OVERLAY_HIDE_AFTER_INVALID) {
      // Only hide after enough consecutive invalid frames
      binding.cornerOverlay.setCorners(null);
      lastFilteredCorners = null;
    }
  }

  /** UI thread: dev overlay with modelRect + metrics when the logging flag is active. */
  private void renderFramingDevOverlay(FramingEval framing, int bmpW, int bmpH) {
    FramingResult fr = framing.result();
    if (!FeatureFlags.isFramingLoggingEnabled() || fr == null || framing.fallbackRect() == null) {
      binding.cornerOverlay.setModelRect(null);
      binding.cornerOverlay.setDebugText(null);
      return;
    }
    binding.cornerOverlay.setModelRect(mapToOverlayRect(framing.fallbackRect(), bmpW, bmpH));
    // Compact debug text. IMPORTANT: Overlay hint must follow the same rhythm as the screen
    // reader. Therefore, we display the last hint emitted by the AccessibilityGuidanceController.
    GuidanceHint hintToShow = lastGuidanceEventHint;
    String dbg =
        String.format(
            Locale.US,
            "q=%.2f\nΔx=%.2f Δy=%.2f\nscale=%.2f\ntiltH=%.2f tiltV=%.2f\nori=%s conf=%s\nhint=%s",
            fr.quality,
            fr.dxNorm,
            fr.dyNorm,
            fr.scaleRatio,
            fr.tiltHorizontal,
            fr.tiltVertical,
            (framing.orientBucket() >= 0 ? (framing.orientBucket() + "°") : "-"),
            (framing.orientConf() >= 0
                ? String.format(Locale.US, "%.2f", framing.orientConf())
                : "-"),
            hintToShow != null ? hintToShow.name() : "-");
    binding.cornerOverlay.setDebugText(dbg);
  }

  private android.graphics.RectF mapToOverlayRect(android.graphics.RectF src, int bmpW, int bmpH) {
    if (binding == null || src == null) return null;
    int vw = binding.viewFinder.getWidth();
    int vh = binding.viewFinder.getHeight();
    if (vw <= 0 || vh <= 0 || bmpW <= 0 || bmpH <= 0) return null;
    float sx = vw / (float) bmpW;
    float sy = vh / (float) bmpH;
    float scale = Math.min(sx, sy);
    float contentW = bmpW * scale;
    float contentH = bmpH * scale;
    float offX = (vw - contentW) * 0.5f;
    float offY = (vh - contentH) * 0.5f;
    return new android.graphics.RectF(
        offX + src.left * scale,
        offY + src.top * scale,
        offX + src.right * scale,
        offY + src.bottom * scale);
  }

  /** Maps A11yStateMachine.Event to GuidanceHint for compatibility with existing code. */
  private GuidanceHint mapEventToHint(A11yStateMachine.Event event) {
    if (event == null) return null;
    switch (event) {
      case READY_ENTER:
        return GuidanceHint.READY_ENTER;
      case HOLD_STILL:
        return GuidanceHint.HOLD_STILL;
      case TILT_LEFT:
        return GuidanceHint.TILT_LEFT;
      case TILT_RIGHT:
        return GuidanceHint.TILT_RIGHT;
      case TILT_FORWARD:
        return GuidanceHint.TILT_FORWARD;
      case TILT_BACK:
        return GuidanceHint.TILT_BACK;
      case MOVE_LEFT:
        return GuidanceHint.MOVE_LEFT;
      case MOVE_RIGHT:
        return GuidanceHint.MOVE_RIGHT;
      case MOVE_UP:
        return GuidanceHint.MOVE_UP;
      case MOVE_DOWN:
        return GuidanceHint.MOVE_DOWN;
      case MOVE_CLOSER:
        return GuidanceHint.MOVE_CLOSER;
      case MOVE_BACK:
        return GuidanceHint.MOVE_BACK;
      case TOO_FAR:
        return GuidanceHint.TOO_FAR;
      case ORIENTATION_PORTRAIT_TIP:
        return GuidanceHint.ORIENTATION_PORTRAIT_TIP;
      case ORIENTATION_LANDSCAPE_TIP:
        return GuidanceHint.ORIENTATION_LANDSCAPE_TIP;
      case OK:
        return GuidanceHint.OK;
      case LOW_LIGHT:
      case CAMERA_READY:
      case FLASHLIGHT_ON:
      case FLASHLIGHT_OFF:
        // System events don't map to guidance hints
        return null;
      default:
        return null;
    }
  }

  private int mapHintToRes(GuidanceHint hint) {
    if (hint == null) return 0;
    switch (hint) {
      case OK:
        return R.string.a11y_hint_ok;
      case MOVE_LEFT:
        return R.string.a11y_hint_move_left;
      case MOVE_RIGHT:
        return R.string.a11y_hint_move_right;
      case MOVE_UP:
        return R.string.a11y_hint_move_up;
      case MOVE_DOWN:
        return R.string.a11y_hint_move_down;
      case MOVE_CLOSER:
        return R.string.a11y_hint_move_closer;
      case MOVE_BACK:
        return R.string.a11y_hint_move_back;
      case TOO_FAR:
        return R.string.a11y_hint_too_far;
      case TILT_LEFT:
        return R.string.a11y_hint_tilt_left;
      case TILT_RIGHT:
        return R.string.a11y_hint_tilt_right;
      case TILT_FORWARD:
        return R.string.a11y_hint_tilt_forward;
      case TILT_BACK:
        return R.string.a11y_hint_tilt_back;
      case NO_DOCUMENT_DETECTED:
        return R.string.a11y_no_document_detected;
      case ORIENTATION_PORTRAIT_TIP:
        return R.string.a11y_orientation_portrait_tip;
      case ORIENTATION_LANDSCAPE_TIP:
        return R.string.a11y_orientation_landscape_tip;
      case HOLD_STILL:
        return R.string.a11y_hold_still;
      case READY_ENTER:
        return R.string.a11y_doc_ready;
    }
    return 0;
  }

  // Optional, very subtle haptics for central hints – only in A11y mode
  // and strictly coupled to the emission cadence of the AccessibilityGuidanceController.
  // No haptics for movement/tilt hints to avoid sensory overload.
  private void maybeVibrateForHint(GuidanceHint hint) {
    if (hint == null) return;
    if (!isAccessibilityModeEnabled()) return; // Only when A11y is active
    Context ctx = getContext();
    if (ctx == null) return;

    long durationMs;
    switch (hint) {
      case NO_DOCUMENT_DETECTED:
        durationMs = 35L; // slightly stronger pulse
        break;
      case ORIENTATION_PORTRAIT_TIP:
      case ORIENTATION_LANDSCAPE_TIP:
        durationMs = 25L; // medium pulse
        break;
      case OK:
        durationMs = 15L; // short confirmation pulse
        break;
      default:
        return; // other hints: no haptics
    }
    // Centralized, SDK-guarded haptics
    HapticsUtils.vibrateOneShot(ctx, durationMs);
  }

  private void runOnUiThreadSafe(Runnable r) {
    if (!isAdded()) return;
    requireActivity().runOnUiThread(r);
  }

  /**
   * Maps detected corner points from the upright analysis-bitmap coordinate space to overlay
   * coordinates on top of the {@link PreviewView} (which uses {@code FIT_CENTER}).
   *
   * <p>The detection bitmap is produced from the full analysis buffer, but — now that all use cases
   * share a common {@link ViewPort} — the preview only renders the cropped field of view. When a
   * {@code cropRect} (already expressed in upright-bitmap space) is supplied, the FIT_CENTER
   * scaling is computed against that visible region instead of the whole bitmap, so the overlay
   * stays aligned with the preview on devices whose analysis crop is a true subset of the buffer.
   * When {@code cropRect} is {@code null}, the full bitmap is used (previous behaviour).
   *
   * @param src the detected corner points in upright-bitmap coordinates
   * @param bmpW width of the upright analysis bitmap
   * @param bmpH height of the upright analysis bitmap
   * @param cropRect the visible (preview) region in upright-bitmap space, or {@code null}
   * @return mapped overlay points, or {@code null} if the view/bitmap is not measurable yet
   */
  private android.graphics.PointF[] mapToOverlayPoints(
      org.opencv.core.Point[] src, int bmpW, int bmpH, @Nullable android.graphics.RectF cropRect) {
    if (binding == null) return null;
    int vw = binding.viewFinder.getWidth();
    int vh = binding.viewFinder.getHeight();
    if (vw <= 0 || vh <= 0 || bmpW <= 0 || bmpH <= 0) return null;

    // Visible region in upright-bitmap space: the preview crop when available, else the full
    // bitmap.
    float regionLeft = 0f;
    float regionTop = 0f;
    float regionW = bmpW;
    float regionH = bmpH;
    if (cropRect != null && cropRect.width() > 0 && cropRect.height() > 0) {
      regionLeft = cropRect.left;
      regionTop = cropRect.top;
      regionW = cropRect.width();
      regionH = cropRect.height();
    }

    float sx = vw / regionW;
    float sy = vh / regionH;
    float scale = Math.min(sx, sy);
    float contentW = regionW * scale;
    float contentH = regionH * scale;
    float offX = (vw - contentW) * 0.5f;
    float offY = (vh - contentH) * 0.5f;
    android.graphics.PointF[] out = new android.graphics.PointF[4];
    for (int i = 0; i < 4; i++) {
      float x = (float) src[i].x;
      float y = (float) src[i].y;
      out[i] =
          new android.graphics.PointF(
              offX + (x - regionLeft) * scale, offY + (y - regionTop) * scale);
    }
    return out;
  }

  /**
   * Computes the visible preview crop region in the upright analysis-bitmap coordinate space.
   *
   * <p>The analysis bitmap is built from the full {@link ImageProxy} buffer (then downscaled and
   * rotated to be upright), whereas the {@link PreviewView} — sharing a common {@link ViewPort} —
   * only shows {@link ImageProxy#getCropRect()}. This helper applies the same downscale and 90°
   * rotation transforms to that crop rectangle so it lines up with the detected corner coordinates.
   *
   * @param image the analysis frame
   * @param maxSize the detection max edge used by {@link #yuvToBitmapUprightSmall}
   * @param bmpW width of the resulting upright bitmap
   * @param bmpH height of the resulting upright bitmap
   * @return the crop region in upright-bitmap space, or {@code null} when the crop covers the whole
   *     buffer (no adjustment needed) or cannot be determined
   */
  @Nullable
  private android.graphics.RectF computeUprightCropRect(
      @NonNull ImageProxy image, int maxSize, int bmpW, int bmpH) {
    android.graphics.Rect crop = image.getCropRect();
    int w = image.getWidth();
    int h = image.getHeight();
    if (crop == null || w <= 0 || h <= 0) return null;
    // Full-buffer crop → preview shows the whole bitmap, keep previous (full-bitmap) mapping.
    if (crop.left <= 0 && crop.top <= 0 && crop.right >= w && crop.bottom >= h) return null;

    int sample = Math.max(1, computeSampleSize(w, h, maxSize));
    float s = 1f / sample;
    float cl = crop.left * s;
    float ct = crop.top * s;
    float cr = crop.right * s;
    float cb = crop.bottom * s;

    // Resized (pre-rotation) bitmap dimensions.
    int rw = Math.max(1, w / sample);
    int rh = Math.max(1, h / sample);

    int rot = ((image.getImageInfo().getRotationDegrees() % 360) + 360) % 360;
    android.graphics.RectF out;
    switch (rot) {
      case 90: // ROTATE_90_CLOCKWISE: upright dims = (rh, rw)
        out = new android.graphics.RectF(rh - cb, cl, rh - ct, cr);
        break;
      case 180:
        out = new android.graphics.RectF(rw - cr, rh - cb, rw - cl, rh - ct);
        break;
      case 270: // ROTATE_90_COUNTERCLOCKWISE: upright dims = (rh, rw)
        out = new android.graphics.RectF(ct, rw - cr, cb, rw - cl);
        break;
      default:
        out = new android.graphics.RectF(cl, ct, cr, cb);
        break;
    }
    // Clamp to the actual upright bitmap bounds to guard against rounding.
    out.left = Math.max(0f, Math.min(out.left, bmpW));
    out.top = Math.max(0f, Math.min(out.top, bmpH));
    out.right = Math.max(0f, Math.min(out.right, bmpW));
    out.bottom = Math.max(0f, Math.min(out.bottom, bmpH));
    if (out.width() <= 0 || out.height() <= 0) return null;
    return out;
  }

  private boolean isAccessibilityModeEnabled() {
    Context ctx = getContext();
    if (ctx == null) return false;
    android.content.SharedPreferences prefs =
        ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
    return prefs.getBoolean(CameraOptionsDialogFragment.BUNDLE_ACCESSIBILITY_MODE, false);
  }

  private boolean isStableFor(int frames, double threshold) {
    if (lastScore > threshold) {
      stableCount++;
    } else {
      stableCount = 0;
    }
    return stableCount >= frames;
  }

  // --- Accessibility mode helper logic ---

  private void maybeSignalGoodFraming() {
    long now = System.currentTimeMillis();
    // Rate limit: max once every 3 seconds
    if (now - lastA11ySignalTs < 3000L) return;
    lastA11ySignalTs = now;
    signalGoodFraming();
  }

  private void signalGoodFraming() {
    // Short system tone
    ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_SYSTEM, 80);
    tg.startTone(ToneGenerator.TONE_PROP_ACK, 80);
    // Haptics (light tap) – centralized via HapticsUtils
    Context ctx = getContext();
    if (ctx != null) {
      HapticsUtils.vibrateOneShot(ctx, 20L);
    }
    // Announcement
    announce(R.string.a11y_doc_ready);
  }

  private void announce(int resId) {
    runOnUiThreadSafe(
        () -> {
          if (binding == null || !isAdded()) return;
          View root = binding.getRoot();
          CharSequence text = getString(resId);
          root.setContentDescription(text);
          A11yUtils.announce(root, text);
        });
  }

  private Bitmap yuvToBitmapUprightSmall(@NonNull ImageProxy image, int maxSize) {
    // Fast path: when OpenCV is initialized, convert NV21 → RGBA directly via Imgproc,
    // resize and rotate as Mats, then blit into a pooled ARGB_8888 Bitmap. This avoids
    // the previous YuvImage→JPEG(Q60)→BitmapFactory round-trip (which was both lossy
    // and CPU-heavy) and reuses a small Bitmap pool to suppress GC churn at ~5–6 FPS.
    // In OpenCV safe mode this path is intentionally disabled: on Android emulators and some
    // affected devices Imgproc.cvtColor can abort the process with SIGILL, which Java cannot catch.
    if (OpenCVUtils.isInitialized() && !OpenCVUtils.isSafeMode()) {
      try {
        return yuvToBitmapUprightSmallCv(image, maxSize);
      } catch (Throwable t) {
        // Defensive: fall through to legacy path on any unexpected CV error.
        Log.w(TAG, "yuvToBitmapUprightSmallCv failed, falling back: " + t.getMessage());
      }
    }
    return yuvToBitmapUprightSmallLegacy(image, maxSize);
  }

  /**
   * OpenCV-based fast path: NV21 → RGBA → resize → rotate → ARGB_8888 Bitmap (pooled). Caller must
   * ensure {@link OpenCVUtils#isInitialized()} is true.
   */
  private Bitmap yuvToBitmapUprightSmallCv(@NonNull ImageProxy image, int maxSize) {
    byte[] nv21 = toNv21(image);
    if (nv21 == null) return null;
    final int w = image.getWidth();
    final int h = image.getHeight();

    // 1) NV21 wrap (single-channel byte mat of height*3/2 rows)
    org.opencv.core.Mat nv21Mat = nv21MatTL.get();
    if (nv21Mat == null
        || nv21Mat.rows() != h + h / 2
        || nv21Mat.cols() != w
        || nv21Mat.type() != org.opencv.core.CvType.CV_8UC1) {
      if (nv21Mat != null) nv21Mat.release();
      nv21Mat = new org.opencv.core.Mat(h + h / 2, w, org.opencv.core.CvType.CV_8UC1);
      nv21MatTL.set(nv21Mat);
    }
    nv21Mat.put(0, 0, nv21, 0, w * h * 3 / 2);

    // 2) NV21 → RGBA
    org.opencv.core.Mat rgbaMat = rgbaMatTL.get();
    if (rgbaMat == null) {
      rgbaMat = new org.opencv.core.Mat();
      rgbaMatTL.set(rgbaMat);
    }
    org.opencv.imgproc.Imgproc.cvtColor(
        nv21Mat, rgbaMat, org.opencv.imgproc.Imgproc.COLOR_YUV2RGBA_NV21);

    // 3) Downscale so the longer edge ≤ maxSize (keep aspect ratio)
    int sample = computeSampleSize(w, h, maxSize);
    int targetW = Math.max(1, w / sample);
    int targetH = Math.max(1, h / sample);
    org.opencv.core.Mat src = rgbaMat;
    org.opencv.core.Mat resized = resizedMatTL.get();
    if (sample > 1) {
      if (resized == null) {
        resized = new org.opencv.core.Mat();
        resizedMatTL.set(resized);
      }
      org.opencv.imgproc.Imgproc.resize(
          rgbaMat,
          resized,
          new org.opencv.core.Size(targetW, targetH),
          0,
          0,
          org.opencv.imgproc.Imgproc.INTER_AREA);
      src = resized;
    }

    // 4) Rotate to upright orientation. CameraX rotationDegrees is clockwise.
    int rot = ((image.getImageInfo().getRotationDegrees() % 360) + 360) % 360;
    org.opencv.core.Mat upright = src;
    if (rot != 0) {
      org.opencv.core.Mat rotated = rotatedMatTL.get();
      if (rotated == null) {
        rotated = new org.opencv.core.Mat();
        rotatedMatTL.set(rotated);
      }
      switch (rot) {
        case 90:
          org.opencv.core.Core.rotate(src, rotated, org.opencv.core.Core.ROTATE_90_CLOCKWISE);
          break;
        case 180:
          org.opencv.core.Core.rotate(src, rotated, org.opencv.core.Core.ROTATE_180);
          break;
        case 270:
          org.opencv.core.Core.rotate(
              src, rotated, org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE);
          break;
        default:
          // Non-orthogonal rotations are not produced by CameraX; treat as no-op.
          rotated = src;
          break;
      }
      upright = rotated;
    }

    // 5) Acquire a pooled ARGB_8888 Bitmap of the upright size and blit Mat into it.
    final int finalW = upright.cols();
    final int finalH = upright.rows();
    Bitmap bmp = acquirePooledBitmap(finalW, finalH);
    org.opencv.android.Utils.matToBitmap(upright, bmp);
    return bmp;
  }

  /**
   * Acquires a Bitmap of the requested dimensions from a small two-slot pool. If neither slot
   * matches, the older slot is recycled and replaced. Returned bitmaps are ARGB_8888 and writable.
   */
  private Bitmap acquirePooledBitmap(int width, int height) {
    Bitmap a = uprightBitmapPoolA.get();
    if (a != null && !a.isRecycled() && a.getWidth() == width && a.getHeight() == height) {
      return a;
    }
    Bitmap b = uprightBitmapPoolB.get();
    if (b != null && !b.isRecycled() && b.getWidth() == width && b.getHeight() == height) {
      return b;
    }
    // No matching slot: place new bitmap into A (recycling the previous A).
    Bitmap fresh = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    if (a != null && !a.isRecycled()) {
      // Move existing A to B (so we keep the most recent two distinct sizes around).
      Bitmap oldB = uprightBitmapPoolB.get();
      if (oldB != null && !oldB.isRecycled()) oldB.recycle();
      uprightBitmapPoolB.set(a);
    }
    uprightBitmapPoolA.set(fresh);
    return fresh;
  }

  /** Returns true iff the given bitmap is currently held in the upright bitmap pool. */
  private boolean isPooledBitmap(@NonNull Bitmap candidate) {
    Bitmap a = uprightBitmapPoolA.get();
    if (a == candidate) return true;
    Bitmap b = uprightBitmapPoolB.get();
    return b == candidate;
  }

  /** Legacy fallback: original YuvImage → JPEG → BitmapFactory path. */
  private Bitmap yuvToBitmapUprightSmallLegacy(@NonNull ImageProxy image, int maxSize) {
    try {
      byte[] nv21 = toNv21(image);
      if (nv21 == null) return null;
      int w = image.getWidth();
      int h = image.getHeight();
      android.graphics.YuvImage yuv =
          new android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null);
      java.io.ByteArrayOutputStream out = jpegReuseStream.get();
      if (out == null) {
        out = new java.io.ByteArrayOutputStream(64 * 1024);
        jpegReuseStream.set(out);
      }
      out.reset();
      yuv.compressToJpeg(new android.graphics.Rect(0, 0, w, h), 60, out);
      byte[] jpeg = out.toByteArray();
      // Decode with inSampleSize
      android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
      opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
      opts.inSampleSize = computeSampleSize(w, h, maxSize);
      Bitmap raw = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
      if (raw == null) return null;
      int rot = image.getImageInfo().getRotationDegrees();
      if (rot == 0) return raw;
      android.graphics.Matrix m = new android.graphics.Matrix();
      m.postRotate(rot);
      Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
      if (rotated != raw) raw.recycle();
      return rotated;
    } catch (Exception e) {
      Log.w(TAG, "yuvToBitmapUprightSmall failed: " + e.getMessage(), e);
      return null;
    }
  }

  private int computeSampleSize(int w, int h, int maxSize) {
    int longSide = Math.max(w, h);
    int sample = 1;
    while (longSide / sample > maxSize) sample <<= 1; // power-of-two downsampling
    return Math.max(1, sample);
  }

  private byte[] toNv21(@NonNull ImageProxy image) {
    ImageProxy.PlaneProxy[] planes = image.getPlanes();
    int width = image.getWidth();
    int height = image.getHeight();
    int needed = width * height * 3 / 2;
    byte[] out = nv21ReuseBuffer.get();
    if (out == null || out.length < needed) {
      out = new byte[needed];
      nv21ReuseBuffer.set(out);
    }
    int offset = 0;

    // ----- Y -----
    java.nio.ByteBuffer yBuf = planes[0].getBuffer();
    int yRowStride = planes[0].getRowStride();
    int yPixStride = planes[0].getPixelStride();

    if (yPixStride == 1 && yRowStride == width) {
      // Fast path: tightly packed
      yBuf.rewind();
      yBuf.get(out, 0, width * height);
      offset = width * height;
    } else {
      // General path
      for (int row = 0; row < height; row++) {
        int yPos = row * yRowStride;
        for (int col = 0; col < width; col++) {
          out[offset++] = yBuf.get(yPos + col * yPixStride);
        }
      }
    }

    // ----- UV → NV21 (VU interleaved) -----
    java.nio.ByteBuffer uBuf = planes[1].getBuffer();
    java.nio.ByteBuffer vBuf = planes[2].getBuffer();
    int uRowStride = planes[1].getRowStride();
    int uPixStride = planes[1].getPixelStride();
    int vRowStride = planes[2].getRowStride();
    int vPixStride = planes[2].getPixelStride();

    int chromaHeight = height / 2;
    int chromaWidth = width / 2;

    // There is no guaranteed tight-pack for UV, so we stay on the safe per-pixel path
    for (int row = 0; row < chromaHeight; row++) {
      int uRowStart = row * uRowStride;
      int vRowStart = row * vRowStride;
      for (int col = 0; col < chromaWidth; col++) {
        int uIndex = uRowStart + col * uPixStride;
        int vIndex = vRowStart + col * vPixStride;
        byte u = uBuf.get(uIndex);
        byte v = vBuf.get(vIndex);
        out[offset++] = v; // NV21 = V then U
        out[offset++] = u;
      }
    }
    return out;
  }

  // Tiered binding (runtime-based escalation instead of vendor checks)
  private enum BindTier {
    PERF,
    COMPAT,
    COMPAT_LOWRES
  }

  // --- Consolidated guidance decision for A11y & debug overlay ---
  // Encapsulates logic: score threshold → NO_DOCUMENT_DETECTED and dampen distance hints

  // ==================== PDF Import Support ====================

  /** Handles image import from gallery (existing logic extracted to method). */
  private void handleImageImport(Uri uri) {
    if (cameraViewModel != null) {
      // Reset rotations for a new scan imported from storage
      if (cropViewModel != null) {
        cropViewModel.setUserRotationDegrees(0);
        cropViewModel.setCaptureRotationDegrees(0);
        cropViewModel.setImageCropped(false);
        cropViewModel.setImageBitmap(null);
      }
      cameraViewModel.setImagePath(null);
      cameraViewModel.setImageUri(uri);
      // Imported/shared image: enable the "already-cropped" fallback heuristic in the crop UI.
      cameraViewModel.setImageSourceIsImported(true);

      // Keep behavior consistent with camera scan: always reset OCR state for a newly imported
      // image.
      OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
      ocrVm.resetForNewImage();

      // Navigate to next step depending on preference
      if (isAdded()) {
        int dest = nextScanFlowDestination();
        try {
          Navigation.findNavController(requireView()).navigate(dest);
        } catch (IllegalArgumentException | IllegalStateException ignored) {
          // Best-effort; failure is non-critical
        }
      }
    }
  }

  /** Delegates PDF import to {@link PdfImportHelper}. */
  private void handlePdfImport(Uri pdfUri) {
    PdfImportHelper.handlePdfImport(this, pdfUri, this::processPdfBitmap, this::processPdfPages);
  }

  /**
   * Handles the multi-page PDF selection: materializes the selected pages sequentially via {@link
   * PdfMultiPageImporter} (each page is persisted to disk immediately and freed from RAM), adds the
   * resulting pages to the export session in PDF order and navigates directly to the export
   * (document overview) screen. The single-page workflow (Crop → OCR → Export) is bypassed; the
   * imported pages are treated as complete pages.
   */
  private void processPdfPages(Uri pdfUri, java.util.List<Integer> pageIndices) {
    if (!isAdded() || pageIndices == null || pageIndices.isEmpty()) return;
    // Session 4: the target DocumentSession must be stable BEFORE the first page is imported.
    // Reuse the runtime document id when the session already carries one (add-page into an
    // existing document); otherwise generate it eagerly so every persisted page can be attached
    // to the same document immediately.
    final de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel sessionVm =
        new ViewModelProvider(requireActivity())
            .get(de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel.class);
    if (sessionVm.getDocumentId() == null) {
      sessionVm.setDocumentId(java.util.UUID.randomUUID().toString());
    }
    final String documentId = sessionVm.getDocumentId();
    final Context appContext = requireContext().getApplicationContext();
    PdfMultiPageImporter.start(
        this,
        pdfUri,
        pageIndices,
        new PdfMultiPageImporter.Listener() {
          @Override
          public void onPagePersisted(
              @NonNull de.schliweb.makeacopy.ui.export.session.CompletedScan page) {
            // Runs on the import thread directly after the page was fully persisted: append the
            // page id to the active DocumentSession atomically. Persist first, then reference —
            // a session never points to a page that does not exist. Duplicates are impossible
            // (append is idempotent and the final runtime sync upserts the same ordered list).
            try {
              de.schliweb.makeacopy.data.DocumentSessionRepository.get(appContext)
                  .appendPageToActive(documentId, page.id());
            } catch (Throwable t) {
              Log.w(TAG, "Incremental DocumentSession append failed", t);
            }
          }

          @Override
          public void onComplete(
              @NonNull
                  java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> imported,
              int failedCount,
              boolean cancelled) {
            if (!isAdded() || imported.isEmpty()) return;
            sessionVm.addAll(imported);
            Context ctx = getContext();
            if (ctx != null) {
              // The import fulfils any pending "Add page" request; clear the flag so
              // ExportFragment does not additionally append a stale cropped bitmap.
              ExportPrefsHelper.clearPendingAddPage(ctx);
            }
            try {
              Navigation.findNavController(requireView()).navigate(R.id.navigation_export);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
              // Best-effort; failure is non-critical
            }
          }
        });
  }

  /**
   * Consumes a pending shared Uri (set by {@link de.schliweb.makeacopy.MainActivity} from an
   * incoming ACTION_SEND/ACTION_VIEW intent) and dispatches it through the existing import pipeline
   * (image -> crop, PDF -> page selection -> crop). The Uri is cleared after consumption so it is
   * processed only once per share.
   */
  private void consumePendingShareIfAny() {
    if (cameraViewModel == null) return;
    Uri uri = cameraViewModel.getPendingShareUri();
    if (uri == null) return;
    String mime = cameraViewModel.getPendingShareMime();
    cameraViewModel.clearPendingShare();

    if (mime == null) {
      try {
        mime = requireContext().getContentResolver().getType(uri);
      } catch (Exception e) {
        Log.w(TAG, "Could not resolve MIME for shared Uri", e);
      }
    }
    if (mime == null) {
      UIUtils.showToast(requireContext(), R.string.error_unknown_file_type, Toast.LENGTH_SHORT);
      return;
    }

    // Defer to the next UI tick so that NavController and view bindings are ready.
    final String finalMime = mime;
    final View root = binding != null ? binding.getRoot() : null;
    Runnable action =
        () -> {
          if (!isAdded()) return;
          if (finalMime.startsWith("image/")) {
            handleImageImport(uri);
          } else if ("application/pdf".equals(finalMime)) {
            handlePdfImport(uri);
          } else {
            UIUtils.showToast(
                requireContext(), R.string.error_unsupported_file_type, Toast.LENGTH_SHORT);
          }
        };
    if (root != null) {
      root.post(action);
    } else {
      action.run();
    }
  }

  /** Processes a bitmap from PDF and feeds it into the normal workflow (Crop → OCR → Export). */
  private void processPdfBitmap(Bitmap bitmap) {
    if (bitmap == null || !isAdded()) return;

    // Reset for new scan
    if (cropViewModel != null) {
      cropViewModel.setUserRotationDegrees(0);
      cropViewModel.setCaptureRotationDegrees(0);
      cropViewModel.setImageCropped(false);
      cropViewModel.setImageBitmap(bitmap);
      cropViewModel.setOriginalImageBitmap(bitmap);
      cropViewModel.setImageLoaded(true);
    }

    if (cameraViewModel != null) {
      cameraViewModel.setImagePath(null); // No file path, from PDF
      cameraViewModel.setImageUri(null); // No direct URI
      // PDF page rendering: treat as imported (already framed page) for the crop UI heuristic.
      cameraViewModel.setImageSourceIsImported(true);
    }

    // Keep behavior consistent with camera scan: reset OCR state for a newly imported PDF page.
    OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
    ocrVm.resetForNewImage();

    // Navigation based on settings
    int dest = nextScanFlowDestination();

    try {
      Navigation.findNavController(requireView()).navigate(dest);
    } catch (IllegalArgumentException | IllegalStateException ignored) {
      // Best-effort; failure is non-critical
    }
  }
}
