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

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentCropBinding;
import de.schliweb.makeacopy.ui.camera.CameraViewModel;
import de.schliweb.makeacopy.utils.image.BitmapUtils;
import de.schliweb.makeacopy.utils.image.CoordinateTransformUtils;
import de.schliweb.makeacopy.utils.image.ImageLoader;
import de.schliweb.makeacopy.utils.image.OpenCVUtils;
import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import de.schliweb.makeacopy.utils.ui.A11yUtils;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.HapticsUtils;
import de.schliweb.makeacopy.utils.ui.TransitionUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;

/**
 * The CropFragment class is a user interface component responsible for handling image cropping
 * operations. It manages the cropping process through interaction with the CropViewModel and
 * CameraViewModel. The fragment provides a cropping UI, handles user input for cropping or
 * resetting the image, and dynamically adjusts UI elements for system insets.
 */
@dagger.hilt.android.AndroidEntryPoint
public class CropFragment extends Fragment {

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Forward-only shared-axis: the camera screen (SurfaceView preview) must not be animated.
    TransitionUtils.applySharedAxisXForwardOnly(this);
  }

  @javax.inject.Inject de.schliweb.makeacopy.ml.docquad.DocQuadOrtRunner docQuadOrtRunner;
  private static final String TAG = "CropFragment";
  private static final long CROP_A11Y_COOLDOWN_MS = 10000L;
  private FragmentCropBinding binding;
  private CropViewModel cropViewModel;
  private CameraViewModel cameraViewModel;
  // Track last applied user rotation to compute deltas for rotating the trapezoid selection
  private int lastUserRotationDeg = 0;
  // Guard: when we update the VM bitmap due to a user rotation, skip handling the immediate
  // imageBitmap observer callback to avoid re-triggering edge detection in the overlay.
  private boolean skipNextBitmapObserver = false;
  private boolean reEditCornersRestored = false;
  // A11y: one-shot post-capture announcement
  private boolean cropA11yAnnounced = false;
  private long cropA11yLastAnnounceTs = 0L;
  // Suppresses the Android system back gesture / back press while the user is actively
  // dragging a corner or edge of the trapezoid. Without this, horizontal drags near the
  // left/right screen edge can trigger Predictive Back and pop the navigation stack.
  private OnBackPressedCallback dragBackBlockCallback;

  /**
   * Inflates the layout for this fragment and initializes all necessary components including
   * ViewModels, UI bindings, and View listeners. It also observes various LiveData objects to
   * dynamically update the UI based on changes in app state.
   *
   * @param inflater The LayoutInflater object that can be used to inflate any views in the
   *     fragment.
   * @param container If non-null, this is the parent view that the fragment's UI should be attached
   *     to. The fragment should not add the view itself, but this can be used to generate the
   *     LayoutParams of the view.
   * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous
   *     saved state as given here.
   * @return The root View for the fragment's UI, or null if the fragment does not provide a UI.
   */
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    OpenCVUtils.init(requireContext());
    cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
    cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);
    binding = FragmentCropBinding.inflate(inflater, container, false);
    View root = binding.getRoot();

    // FR #72 — Re-Edit entry from Export:
    // When the user re-enters CropFragment via the Export edit-overlay, the in-memory
    // original image bitmap has typically been released by ExportFragment to save memory.
    // Re-decode it from the on-disk capture (or shared URI) so performCrop has the same
    // full-resolution source as in the first pass. The cropped flag must be cleared so the
    // imageBitmap observer takes the crop-mode branch (instead of immediately navigating).
    if (Boolean.TRUE.equals(cropViewModel.isCameFromExport().getValue())) {
      try {
        Integer lastRot = cropViewModel.getLastAcceptedUserRotationDeg().getValue();
        int lr = lastRot == null ? 0 : ((lastRot % 360) + 360) % 360;
        if (lr != 0) {
          // Rebuild the Re-Edit working bitmap from a known unrotated baseline. Otherwise a
          // retained userRotationDegrees value equal to the last accepted rotation can make the
          // bitmap observer apply persisted corners to the freshly reloaded original before the
          // rotation observer has recreated the accepted working orientation.
          cropViewModel.setUserRotationDegrees(0);
        }
        reEditCornersRestored = false;
        Bitmap original = cropViewModel.getOriginalImageBitmap().getValue();
        if (original == null || original.isRecycled()) {
          String path =
              cameraViewModel != null && cameraViewModel.getImagePath() != null
                  ? cameraViewModel.getImagePath().getValue()
                  : null;
          Uri u =
              cameraViewModel != null && cameraViewModel.getImageUri() != null
                  ? cameraViewModel.getImageUri().getValue()
                  : null;
          Bitmap reloaded = ImageLoader.decode(requireContext(), path, u);
          if (reloaded != null) {
            cropViewModel.setOriginalImageBitmap(reloaded);
            // Reset cropped flag and write the original back as the working bitmap, so the
            // observer below shows the crop UI and the trapezoid view receives the unrotated
            // source. The user rotation will be re-applied via the existing rotation observer.
            cropViewModel.setImageCropped(false);
            cropViewModel.setImageBitmap(reloaded);
          } else {
            android.util.Log.w(
                TAG, "[FR72] Re-Edit: failed to reload original from path=" + path + " uri=" + u);
          }
        } else {
          // Original still in-memory: force the crop UI by clearing cropped + setting bitmap.
          cropViewModel.setImageCropped(false);
          cropViewModel.setImageBitmap(original);
        }
      } catch (Throwable t) {
        android.util.Log.w(TAG, "[FR72] Re-Edit: reload failed: " + t.getMessage(), t);
      }
    }

    // Pass the Hilt-injected DocQuadOrtRunner to the TrapezoidSelectionView
    binding.trapezoidSelection.setDocQuadOrtRunner(docQuadOrtRunner);

    // Hint binding: when at least one corner of the trapezoid leaves the original image
    // rectangle (off-image), show a dedicated hint warning the user that the warped output
    // may contain black borders. Otherwise restore the standard adjust-corners instruction.
    // See docs/edge_drag_pan_zoom_concept.md §5.3.
    binding.trapezoidSelection.setOnCornersChangedListener(
        anyCornerOffImage -> {
          if (binding == null) return;
          if (anyCornerOffImage) {
            binding.textCrop.setText(R.string.crop_hint_corner_off_image);
          } else {
            binding.textCrop.setText(
                R.string
                    .adjust_the_trapezoid_corners_to_select_the_area_to_crop_then_tap_the_crop_button);
          }
        });

    // Block the system Back gesture / Back press while a corner or edge drag is in progress.
    // The callback is registered up-front but disabled, then enabled only for the duration of
    // an active drag, so normal back navigation continues to work outside drag operations.
    dragBackBlockCallback =
        new OnBackPressedCallback(false) {
          @Override
          public void handleOnBackPressed() {
            // Intentionally swallow back while dragging.
          }
        };
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(getViewLifecycleOwner(), dragBackBlockCallback);
    binding.trapezoidSelection.setOnDragStateChangedListener(
        isDragging -> {
          if (dragBackBlockCallback != null) {
            dragBackBlockCallback.setEnabled(isDragging);
          }
        });

    // Pan/Zoom (Phase 2 step 2/4/5): keep the underlying image_to_crop ImageView's render
    // matrix in sync with TrapezoidSelectionView.viewMatrix so that the bitmap and overlay
    // pan/zoom together. The flag stays default-off in releases — when off, this listener
    // still fires once on registration with identity transform (and we restore fitCenter),
    // so existing behaviour is unaffected. See docs/edge_drag_pan_zoom_concept.md §4.1.
    binding.trapezoidSelection.setOnViewTransformChangedListener(
        (scale, tx, ty, viewMatrix) -> {
          if (binding == null) return;
          if (!de.schliweb.makeacopy.BuildConfig.FEATURE_CROP_PAN_ZOOM
              || (Math.abs(scale - 1f) < 1e-4f && Math.abs(tx) < 1e-4f && Math.abs(ty) < 1e-4f)) {
            // Identity (or feature off): restore default fitCenter rendering. This avoids any
            // residual matrix from a previous interaction polluting the next bitmap load.
            if (binding.imageToCrop.getScaleType()
                != android.widget.ImageView.ScaleType.FIT_CENTER) {
              binding.imageToCrop.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
              binding.imageToCrop.setImageMatrix(new android.graphics.Matrix());
            }
            // Drop any clip set during a previous zoom session so the unzoomed view returns
            // to its normal (unclipped) rendering.
            binding.imageToCrop.setClipBounds(null);
            return;
          }
          // Confine the scaled bitmap rendering to the ImageView bounds so the parent
          // ConstraintLayout's clipChildren="false" does not let the zoomed bitmap overflow
          // into adjacent layout regions.
          int cw = binding.imageToCrop.getWidth();
          int ch = binding.imageToCrop.getHeight();
          if (cw > 0 && ch > 0) {
            binding.imageToCrop.setClipBounds(new android.graphics.Rect(0, 0, cw, ch));
          }
          // Compute baseFitMatrix that reproduces fitCenter for the current bitmap+view, then
          // pre-concatenate viewMatrix so the bitmap moves with the overlay.
          android.graphics.drawable.Drawable d = binding.imageToCrop.getDrawable();
          if (d == null) return;
          int dw = d.getIntrinsicWidth();
          int dh = d.getIntrinsicHeight();
          int vw = binding.imageToCrop.getWidth();
          int vh = binding.imageToCrop.getHeight();
          if (dw <= 0 || dh <= 0 || vw <= 0 || vh <= 0) return;
          android.graphics.Matrix base = new android.graphics.Matrix();
          android.graphics.RectF src = new android.graphics.RectF(0, 0, dw, dh);
          android.graphics.RectF dst = new android.graphics.RectF(0, 0, vw, vh);
          base.setRectToRect(src, dst, android.graphics.Matrix.ScaleToFit.CENTER);
          // Final = viewMatrix · base (viewMatrix operates on view coordinates, base maps
          // bitmap → view).
          android.graphics.Matrix combined = new android.graphics.Matrix(viewMatrix);
          combined.preConcat(base);
          if (binding.imageToCrop.getScaleType() != android.widget.ImageView.ScaleType.MATRIX) {
            binding.imageToCrop.setScaleType(android.widget.ImageView.ScaleType.MATRIX);
          }
          binding.imageToCrop.setImageMatrix(combined);
        });

    // Wire the Magnifier source view: compute and pass image->overlay matrix once layout/bitmap
    // ready
    // Try immediately; if sizes are 0 we'll retry after bitmap/layout
    tryUpdateMagnifierMapping();

    cropViewModel.getText().observe(getViewLifecycleOwner(), binding.textCrop::setText);

    ViewCompat.setOnApplyWindowInsetsListener(
        binding.cropButtonContainer,
        (v, insets) -> {
          UIUtils.adjustMarginForSystemInsets(binding.cropButtonContainer, 8);
          return insets;
        });

    // Initial UI-Mode
    showCropMode();
    // Ensure hint text in overlay avoids bottom UI
    binding.getRoot().post(this::updateTrapezoidHintInset);

    // Back button: return to Camera for a fresh scan — except for the FR #72 Re-Edit flow,
    // where Back must return to Export without losing the captured image, OCR result, etc.
    binding.buttonBack.setOnClickListener(
        v -> {
          if (Boolean.TRUE.equals(cropViewModel.isCameFromExport().getValue())) {
            cropViewModel.setCameFromExport(false);
            cropViewModel.setReEditPageIndex(-1);
            try {
              boolean popped =
                  Navigation.findNavController(requireView())
                      .popBackStack(R.id.navigation_export, false);
              if (!popped) {
                Navigation.findNavController(requireView()).navigate(R.id.navigation_export);
              }
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }
            return;
          }
          try {
            // Reset state for a fresh scan
            cropViewModel.setImageCropped(false);
            cropViewModel.setUserRotationDegrees(0);
            cropViewModel.setCaptureRotationDegrees(0);
            // Clear current image references so Camera shows live preview
            if (cameraViewModel != null) {
              cameraViewModel.setImageUri(null);
              cameraViewModel.setImagePath(null);
            }
          } catch (Throwable ignored) {
            // Best-effort; failure is non-critical
          }
          // Navigate back to Camera: disable the shared-axis exit transition for this hop,
          // the camera's SurfaceView preview must not be animated by view transitions.
          setExitTransition(null);
          NavOptions navOptions =
              new NavOptions.Builder().setPopUpTo(R.id.navigation_camera, true).build();
          Navigation.findNavController(requireView())
              .navigate(R.id.navigation_camera, null, navOptions);
        });

    // Crop-Button
    binding.buttonCrop.setOnClickListener(
        v -> {
          // Confirm the crop action with a short haptic tick
          HapticsUtils.vibrateOneShot(getContext(), 20L);
          performCrop();
        });

    // Rotation buttons (now available pre-crop)
    binding.buttonRotateLeft.setOnClickListener(v -> cropViewModel.rotateLeft());
    binding.buttonRotateRight.setOnClickListener(v -> cropViewModel.rotateRight());

    // Aspect-Ratio selector (stage B; see docs/aspect_ratio_concept_v3.8.0.md)
    setupAspectButton();

    // Snap-to-Right-Angle toggle (FR #72 companion; see docs/fr72_edit_shape_from_export_concept.md
    // §5)
    setupSnapToggleButton();

    // Curved-edges toggle (Issue #91: manual dewarping of curved book pages)
    setupCurvedToggleButton();

    // Perspective-depth slider (Issue #91, Phase 3)
    setupDepthSlider();

    // Bitmap-Change
    cropViewModel
        .getImageBitmap()
        .observe(
            getViewLifecycleOwner(),
            bitmap -> {
              if (skipNextBitmapObserver) {
                // Skip handling caused by our own rotation writeback to prevent retriggering edge
                // detection
                skipNextBitmapObserver = false;
                return;
              }
              if (bitmap != null) {
                if (Boolean.TRUE.equals(cropViewModel.isImageCropped().getValue())) {
                  android.util.Log.d(
                      TAG,
                      "[CROP_LOG] imageBitmap observer: isCropped=true, navigating. bmp="
                          + bitmap.getWidth()
                          + "x"
                          + bitmap.getHeight());
                  navigateAfterCrop();
                } else {
                  showCropMode();
                  Bitmap safe = BitmapUtils.ensureDisplaySafe(bitmap);
                  binding.imageToCrop.setImageBitmap(safe);
                  // Gate the "already-cropped" fallback heuristic so it only runs for
                  // imported/shared images (gallery, share intent, PDF page) and never for
                  // live camera captures, where the heuristic must not interfere with
                  // genuine document-corner detection on the captured frame.
                  binding.trapezoidSelection.setPreCroppedHint(
                      cameraViewModel != null && cameraViewModel.isImageSourceImported());
                  binding.trapezoidSelection.setImageBitmap(safe);
                  // FR #72 — Re-Edit: pre-populate trapezoid corners from the previously
                  // accepted crop and apply the previously accepted user rotation, so the
                  // user re-enters the exact same shape they had on the first pass instead
                  // of starting from auto-detection. The corners are stored in coordinates
                  // of the rotated full-res source; we apply them directly because the
                  // displayed bitmap has the same aspect ratio (the trapezoid view scales
                  // image coords → view coords on its own).
                  if (Boolean.TRUE.equals(cropViewModel.isCameFromExport().getValue())) {
                    try {
                      Integer lastRot = cropViewModel.getLastAcceptedUserRotationDeg().getValue();
                      Integer curRot = cropViewModel.getUserRotationDegrees().getValue();
                      int lr = lastRot == null ? 0 : lastRot;
                      int cr = curRot == null ? 0 : ((curRot % 360) + 360) % 360;
                      if (lr != cr) {
                        // First rebuild the working bitmap in the same orientation in which the
                        // corners were accepted. The persisted corners are already in that rotated
                        // source coordinate space, so applying them before this rotation would put
                        // a rotated trapezoid onto the wrong image.
                        reEditCornersRestored = false;
                        cropViewModel.setUserRotationDegrees(lr);
                        return;
                      }
                      applyPersistedReEditCornersIfNeeded();
                    } catch (Throwable t) {
                      android.util.Log.w(
                          TAG, "[FR72] Re-Edit: corner pre-population failed: " + t.getMessage());
                    }
                  }
                  if (Boolean.TRUE.equals(cropViewModel.isCameFromExport().getValue())) {
                    Integer curRot = cropViewModel.getUserRotationDegrees().getValue();
                    lastUserRotationDeg = curRot == null ? 0 : ((curRot % 360) + 360) % 360;
                  } else {
                    lastUserRotationDeg = 0; // reset rotation baseline for selection sync
                  }
                  // Disable rotation while edge detection likely runs, then re-enable shortly
                  setRotationButtonsEnabled(false);
                  binding.trapezoidSelection.postDelayed(
                      () -> setRotationButtonsEnabled(true), 600);
                  // Update hint inset to avoid overlapping the rotation bar
                  binding.getRoot().post(this::updateTrapezoidHintInset);
                  // With a new bitmap, recompute and wire the magnifier mapping
                  tryUpdateMagnifierMapping();

                  // Post-capture: announce detection summary once in Accessibility Mode
                  maybeAnnounceCropDetectionOnce(safe);

                  // Dev-Overlay: zeige modelRect im Crop-Screen, wenn Logging-Flag aktiv
                  if (FeatureFlags.isFramingLoggingEnabled()) {
                    binding.cropDevOverlay.setVisibility(View.VISIBLE);
                    // Nach Layout-Pass mappen, falls Größen noch 0 sind
                    binding.cropDevOverlay.post(() -> updateDevOverlayForBitmap(safe));
                  } else {
                    try {
                      binding.cropDevOverlay.setModelRect(null);
                      binding.cropDevOverlay.setDebugText(null);
                      binding.cropDevOverlay.setVisibility(View.GONE);
                    } catch (Throwable ignore) {
                      // Best-effort; failure is non-critical
                    }
                  }
                }
              }
            });

    cropViewModel
        .isImageCropped()
        .observe(
            getViewLifecycleOwner(),
            isCropped -> {
              if (Boolean.TRUE.equals(isCropped)
                  && cropViewModel.getImageBitmap().getValue() != null) {
                navigateAfterCrop();
              }
            });
    // React to rotation changes while in crop mode: rotate the original image and update previews
    cropViewModel
        .getUserRotationDegrees()
        .observe(
            getViewLifecycleOwner(),
            degObj -> {
              if (Boolean.TRUE.equals(cropViewModel.isImageCropped().getValue())) return;
              Bitmap original = cropViewModel.getOriginalImageBitmap().getValue();
              if (original == null) original = cropViewModel.getImageBitmap().getValue();
              if (original == null) return;
              int deg = degObj == null ? 0 : ((degObj % 360) + 360) % 360;
              Bitmap safe = BitmapUtils.ensureDisplaySafe(original);
              try {
                if (deg != 0) {
                  android.graphics.Matrix m = new android.graphics.Matrix();
                  m.postRotate(deg);
                  Bitmap rotated =
                      android.graphics.Bitmap.createBitmap(
                          safe, 0, 0, safe.getWidth(), safe.getHeight(), m, true);
                  if (rotated != null) safe = rotated;
                }
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
              // Update both the view and the VM bitmap; rotate trapezoid selection with the image
              binding.imageToCrop.setImageBitmap(safe);
              int delta = (deg - lastUserRotationDeg);
              // Normalize delta to [-270, 270] equivalent CW degrees
              delta = ((delta % 360) + 360) % 360;
              try {
                int correctedDelta =
                    (360 - (delta % 360) + 360) % 360; // invert to match bitmap rotation direction
                binding.trapezoidSelection.setImageBitmapWithRotation(safe, correctedDelta);
              } catch (Throwable t) {
                // Fallback: set normally if new API fails
                binding.trapezoidSelection.setImageBitmap(safe);
              }
              lastUserRotationDeg = deg;
              // Prevent the subsequent imageBitmap observer from re-initializing edges due to our
              // own writeback
              skipNextBitmapObserver = true;
              cropViewModel.setImageBitmap(safe);
              if (Boolean.TRUE.equals(cropViewModel.isCameFromExport().getValue())) {
                applyPersistedReEditCornersIfNeeded();
              }
              tryUpdateMagnifierMapping();

              // Dev-Overlay im Crop: nach Rotation neu mappen, wenn Flag aktiv
              if (FeatureFlags.isFramingLoggingEnabled()) {
                final Bitmap safeFinal = safe;
                binding.cropDevOverlay.post(() -> updateDevOverlayForBitmap(safeFinal));
              }
            });

    cameraViewModel
        .getImageUri()
        .observe(
            getViewLifecycleOwner(),
            uri -> {
              if (uri == null) return;
              if (Boolean.TRUE.equals(cropViewModel.isCameFromExport().getValue())) {
                Bitmap preparedReEditBitmap = cropViewModel.getImageBitmap().getValue();
                if (preparedReEditBitmap != null) {
                  // FR #72 Re-Edit prepares the working bitmap itself from the persisted original
                  // and the last accepted rotation. The camera URI observer can fire afterwards and
                  // would otherwise overwrite the rotated crop preview with the unrotated original,
                  // leaving the persisted (rotated) trapezoid on top of the wrong image.
                  return;
                }
              }
              // Do not reload the original image if we already have a cropped image.
              Boolean alreadyCropped = cropViewModel.isImageCropped().getValue();
              if (Boolean.TRUE.equals(alreadyCropped)) {
                // Keep the current cropped bitmap in the CropViewModel.
                return;
              }
              loadImageFromUri(uri);
            });

    // Window Insets
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          UIUtils.adjustTextViewTopMarginForStatusBar(binding.textCrop, 8);
          UIUtils.adjustMarginForSystemInsets(binding.cropButtonContainer, 80);
          return insets;
        });

    return root;
  }

  private boolean isAccessibilityModeEnabled() {
    try {
      android.content.Context ctx = requireContext();
      android.content.SharedPreferences prefs =
          ctx.getSharedPreferences("export_options", android.content.Context.MODE_PRIVATE);
      return prefs.getBoolean(
          de.schliweb.makeacopy.ui.camera.CameraOptionsDialogFragment.BUNDLE_ACCESSIBILITY_MODE,
          false);
    } catch (Throwable ignored) {
      return false;
    }
  }

  private void maybeAnnounceCropDetectionOnce(@NonNull Bitmap bmp) {
    if (!isAccessibilityModeEnabled()) return;
    long now = System.currentTimeMillis();
    if (cropA11yAnnounced && (now - cropA11yLastAnnounceTs) < CROP_A11Y_COOLDOWN_MS) return;
    cropA11yAnnounced = true;
    cropA11yLastAnnounceTs = now;
    new Thread(
            () -> {
              try {
                // Ensure OpenCV is ready
                OpenCVUtils.init(requireContext().getApplicationContext());
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }

              boolean hasValid = false;

              // Use DocQuad detector with OpenCV fallback (via forCrop)
              try {
                de.schliweb.makeacopy.ml.corners.CornerDetector detector =
                    de.schliweb.makeacopy.ml.corners.CornerDetectorFactory.forCrop(
                        requireContext(), docQuadOrtRunner);
                de.schliweb.makeacopy.ml.corners.DetectionResult r =
                    detector.detect(bmp, requireContext());
                if (r != null
                    && r.success
                    && r.cornersOriginalTLTRBRBL != null
                    && r.cornersOriginalTLTRBRBL.length == 4) {
                  hasValid = true;
                }
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }

              final boolean finalHasValid = hasValid;
              if (!isAdded() || binding == null) return;
              requireActivity()
                  .runOnUiThread(
                      () -> {
                        if (binding == null) return;
                        if (finalHasValid) {
                          announceText(getString(R.string.a11y_crop_summary_detected));
                        } else {
                          // Be polite: only a short non-intrusive note
                          announceText(getString(R.string.a11y_crop_summary_no_doc));
                        }
                      });
            },
            "CropA11yAnnounce")
        .start();
  }

  private void announceText(@NonNull CharSequence text) {
    if (binding == null) return;
    View root = binding.getRoot();
    root.setContentDescription(text);
    A11yUtils.announce(root, text);
  }

  private void tryUpdateMagnifierMapping() {
    if (binding == null) return;
    final Bitmap bmp = cropViewModel != null ? cropViewModel.getImageBitmap().getValue() : null;
    final ImageView imageView = binding.imageToCrop;
    final View overlay = binding.trapezoidSelection;
    if (bmp == null) return;
    overlay.post(() -> ensureMagnifierMapping(bmp, imageView, overlay, 0));
  }

  private void ensureMagnifierMapping(
      Bitmap bitmap, ImageView imageView, View overlay, int attempt) {
    if (!isAdded()) return;
    int ow = overlay.getWidth();
    int oh = overlay.getHeight();
    int vw = imageView.getWidth();
    int vh = imageView.getHeight();
    if (ow <= 0 || oh <= 0 || vw <= 0 || vh <= 0) {
      if (attempt < 6) {
        overlay.postDelayed(
            () -> ensureMagnifierMapping(bitmap, imageView, overlay, attempt + 1), 50);
      }
      return;
    }
    // Use the ImageView as magnifier source view and rely on robust screen-space mapping
    binding.trapezoidSelection.setMagnifierSourceView(imageView, null);
  }

  /**
   * Loads an image from the provided URI, sets the image URI and bitmaps in the crop view model,
   * and handles any errors if the image cannot be loaded.
   *
   * @param uri The URI of the image to be loaded.
   */
  private void loadImageFromUri(Uri uri) {
    String path =
        cameraViewModel != null && cameraViewModel.getImagePath() != null
            ? cameraViewModel.getImagePath().getValue()
            : null;
    Bitmap bitmap = ImageLoader.decode(requireContext(), path, uri);
    if (bitmap != null) {
      cropViewModel.setImageUri(uri);
      cropViewModel.setImageBitmap(bitmap);
      cropViewModel.setOriginalImageBitmap(bitmap);
    } else {
      // Error Handling: show friendly message
      UIUtils.showToast(
          requireContext(),
          getString(R.string.error_displaying_image, "decode failed"),
          android.widget.Toast.LENGTH_SHORT);
    }
  }

  private void applyPersistedReEditCornersIfNeeded() {
    if (reEditCornersRestored || binding == null || cropViewModel == null) return;
    try {
      android.graphics.PointF[] persisted =
          cropViewModel.getLastAcceptedCornersOriginal().getValue();
      if (persisted == null || persisted.length != 4) return;

      org.opencv.core.Point[] pts = new org.opencv.core.Point[4];
      for (int i = 0; i < 4; i++) {
        if (persisted[i] == null) return;
        pts[i] = new org.opencv.core.Point(persisted[i].x, persisted[i].y);
      }
      Bitmap displayedBitmap = cropViewModel.getImageBitmap().getValue();
      if (displayedBitmap == null
          || !TrapezoidSelectionView.isValidImageQuad(
              pts, displayedBitmap.getWidth(), displayedBitmap.getHeight())) {
        android.util.Log.w(TAG, "[FR72] Re-Edit: ignoring invalid persisted corners");
        return;
      }
      binding.trapezoidSelection.setCornersFromImageCoordinates(pts);
      reEditCornersRestored = true;
      android.util.Log.d(TAG, "[FR72] Re-Edit: pre-populated trapezoid from persisted corners");
    } catch (Throwable t) {
      android.util.Log.w(TAG, "[FR72] Re-Edit: corner pre-population failed: " + t.getMessage());
    }
  }

  /**
   * Performs the cropping operation on the current image bitmap.
   *
   * <p>This method checks if a valid image bitmap exists in the cropViewModel. If no bitmap is
   * present, the method exits early. It ensures OpenCV is initialized, transforming the corners of
   * a trapezoid selection from view coordinates to image coordinates if available. Using OpenCV
   * utilities, the method applies a perspective correction based on the transformed trapezoid
   * corners. If the perspective correction operation produces a valid cropped bitmap, it updates
   * the cropViewModel with the new cropped bitmap and marks the image as cropped.
   *
   * <p>Key operations: - Verifies a bitmap is available in the cropViewModel. - Initializes OpenCV
   * if it has not yet been initialized. - Retrieves and transforms trapezoid selection corners, if
   * present. - Performs perspective correction using OpenCV to generate a cropped bitmap. - Updates
   * the cropViewModel with the new cropped image and sets the cropped flag to true.
   */
  private void performCrop() {
    final String LP = "[CROP_LOG] ";
    // The image currently shown in the ImageView (display-safe, possibly scaled)
    Bitmap displayedBitmap = cropViewModel.getImageBitmap().getValue();
    if (displayedBitmap == null) {
      android.util.Log.w(TAG, LP + "performCrop: No bitmap available");
      return;
    }
    if (!OpenCVUtils.isInitialized()) OpenCVUtils.init(requireContext());

    // Use the full-resolution original, rotated by the current user rotation, as crop source.
    // Rationale: The trapezoid selection and preview are shown with the user rotation applied.
    // To ensure that the perspective correction matches what the user sees (and to avoid
    // quality loss by re-rotating later), we bake the user rotation into the source used
    // for cropping. originalImageBitmap itself remains unmodified and is only used as input here.
    Bitmap fullResSource = null;
    try {
      Bitmap orig = cropViewModel.getOriginalImageBitmap().getValue();
      Integer ur = cropViewModel.getUserRotationDegrees().getValue();
      int userDeg = ur == null ? 0 : ((ur % 360) + 360) % 360;
      if (orig != null && !orig.isRecycled()) {
        if (userDeg != 0) {
          android.graphics.Matrix m = new android.graphics.Matrix();
          m.postRotate(userDeg);
          fullResSource =
              android.graphics.Bitmap.createBitmap(
                  orig, 0, 0, orig.getWidth(), orig.getHeight(), m, true);
          android.util.Log.d(
              TAG, LP + "performCrop: applying user rotation " + userDeg + "° to full-res source");
        } else {
          fullResSource = orig;
          android.util.Log.d(
              TAG, LP + "performCrop: user rotation 0°, using original full-res source");
        }
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    if (fullResSource == null) {
      // Fallback: crop the currently displayed bitmap (might be scaled)
      fullResSource = displayedBitmap;
    }

    android.util.Log.d(
        TAG,
        LP
            + "performCrop: displayed="
            + displayedBitmap.getWidth()
            + "x"
            + displayedBitmap.getHeight()
            + ", source(full-res?)="
            + fullResSource.getWidth()
            + "x"
            + fullResSource.getHeight());

    // 1) Get corners in displayed-bitmap image coordinates
    org.opencv.core.Point[] imgCornersDisplay = null;
    try {
      org.opencv.core.Point[] viewCorners = binding.trapezoidSelection.getCorners();
      imgCornersDisplay =
          CoordinateTransformUtils.transformViewToImageCoordinates(
              viewCorners, displayedBitmap, binding.imageToCrop);
      android.util.Log.d(
          TAG,
          LP
              + "performCrop: transformed corners (display)="
              + (imgCornersDisplay != null
                  ? java.util.Arrays.toString(imgCornersDisplay)
                  : "null"));
    } catch (Throwable t) {
      android.util.Log.w(TAG, LP + "performCrop: corner transform failed: " + t.getMessage());
    }
    // Guard: if we have no valid corners, do not proceed to crop; avoid forwarding the original
    // image or warping with a degenerate quadrilateral.
    if (!TrapezoidSelectionView.isValidImageQuad(
        imgCornersDisplay, displayedBitmap.getWidth(), displayedBitmap.getHeight())) {
      android.util.Log.w(
          TAG,
          LP + "performCrop: No valid corners available; aborting crop and staying in crop UI");
      UIUtils.showToast(
          requireContext(),
          getString(
              R.string
                  .adjust_the_trapezoid_corners_to_select_the_area_to_crop_then_tap_the_crop_button),
          android.widget.Toast.LENGTH_SHORT);
      return;
    }

    // 2) If we are using a higher-resolution source than displayed, scale corners accordingly
    org.opencv.core.Point[] cornersForSource;
    if (fullResSource != displayedBitmap) {
      float sx = fullResSource.getWidth() / (float) displayedBitmap.getWidth();
      float sy = fullResSource.getHeight() / (float) displayedBitmap.getHeight();
      cornersForSource = new org.opencv.core.Point[4];
      for (int i = 0; i < 4; i++) {
        cornersForSource[i] =
            new org.opencv.core.Point(imgCornersDisplay[i].x * sx, imgCornersDisplay[i].y * sy);
      }
      android.util.Log.d(
          TAG,
          LP
              + String.format(
                  java.util.Locale.US,
                  "performCrop: scaling corners to full-res (sx=%.4f, sy=%.4f) → %s",
                  sx,
                  sy,
                  java.util.Arrays.toString(cornersForSource)));
    } else {
      cornersForSource = imgCornersDisplay;
    }

    // Issue #91: in curved mode, additionally transform the two on-curve midpoints (top/bottom
    // edge) from view → displayed-image coordinates and scale them to full-res exactly like the
    // corners above, then build the DewarpModel. If anything fails, dewarpModel stays null and
    // the existing straight perspective-correction path below is used unchanged.
    de.schliweb.makeacopy.utils.image.DewarpModel dewarpModel = null;
    if (binding.trapezoidSelection.isCurvedMode()) {
      try {
        org.opencv.core.Point[] midsView = binding.trapezoidSelection.getCurveMidpointsViewCoords();
        org.opencv.core.Point[] midsDisplay =
            CoordinateTransformUtils.transformViewToImageCoordinates(
                midsView, displayedBitmap, binding.imageToCrop);
        if (midsDisplay != null
            && midsDisplay.length == 2
            && midsDisplay[0] != null
            && midsDisplay[1] != null) {
          double sx = 1.0;
          double sy = 1.0;
          if (fullResSource != displayedBitmap) {
            sx = fullResSource.getWidth() / (double) displayedBitmap.getWidth();
            sy = fullResSource.getHeight() / (double) displayedBitmap.getHeight();
          }
          org.opencv.core.Point topMid =
              new org.opencv.core.Point(midsDisplay[0].x * sx, midsDisplay[0].y * sy);
          org.opencv.core.Point bottomMid =
              new org.opencv.core.Point(midsDisplay[1].x * sx, midsDisplay[1].y * sy);
          dewarpModel =
              de.schliweb.makeacopy.utils.image.DewarpModel.fromOnCurveMidpoints(
                  cornersForSource, topMid, bottomMid);
          if (dewarpModel != null
              && (estimatedTopEdgeProfile != null || estimatedBottomEdgeProfile != null)) {
            // Issue #91 follow-up (corner accuracy): carry the traced edge SHAPE from the
            // automatic estimation into the warp; the profiles are chord-normalized and get
            // rescaled to the user's current curve-handle positions inside the model.
            dewarpModel =
                dewarpModel.withEdgeProfiles(estimatedTopEdgeProfile, estimatedBottomEdgeProfile);
          }
          if (dewarpModel != null && dewarpDepth != 0.0) {
            // Issue #91 (Phase 3): apply the perspective-depth fine adjustment from the slider.
            dewarpModel = dewarpModel.withDepth(dewarpDepth);
          }
          android.util.Log.d(
              TAG,
              LP
                  + "performCrop: curved mode, topMid="
                  + topMid
                  + ", bottomMid="
                  + bottomMid
                  + ", depth="
                  + dewarpDepth);
        } else {
          android.util.Log.w(
              TAG, LP + "performCrop: curve midpoint transform invalid; falling back to straight");
        }
      } catch (Throwable t) {
        android.util.Log.w(
            TAG, LP + "performCrop: curved-mode midpoint transform failed, falling back: " + t);
        dewarpModel = null;
      }
    }

    long t0 = android.os.SystemClock.uptimeMillis();
    Bitmap croppedBitmap;
    {
      CropAspectRatio sel = CropPrefsHelper.getLastAspect(requireContext());
      if (sel == CropAspectRatio.ORIGINAL) {
        if (dewarpModel != null) {
          // Issue #91: the legacy heuristic has no WarpMode counterpart; use AUTO_PROJECTIVE
          // for dewarping so the curved selection is honored under aspect=ORIGINAL as well.
          android.util.Log.d(TAG, LP + "performCrop: aspect=ORIGINAL → dewarp AUTO_PROJECTIVE");
          croppedBitmap =
              OpenCVUtils.applyDewarp(
                  fullResSource, dewarpModel, OpenCVUtils.WarpMode.AUTO_PROJECTIVE, null);
        } else {
          android.util.Log.d(TAG, LP + "performCrop: aspect=ORIGINAL → legacy heuristic");
          croppedBitmap =
              OpenCVUtils.applyPerspectiveCorrectionLegacyHeuristic(
                  fullResSource, cornersForSource);
        }
      } else {
        Double ratio = CropPrefsHelper.resolveActiveRatio(requireContext());
        if (ratio == null) {
          android.util.Log.d(
              TAG,
              LP
                  + "performCrop: aspect="
                  + sel
                  + " → AUTO_PROJECTIVE"
                  + (dewarpModel != null ? " (dewarp)" : ""));
          croppedBitmap =
              dewarpModel != null
                  ? OpenCVUtils.applyDewarp(
                      fullResSource, dewarpModel, OpenCVUtils.WarpMode.AUTO_PROJECTIVE, null)
                  : OpenCVUtils.applyPerspectiveCorrection(
                      fullResSource, cornersForSource, OpenCVUtils.WarpMode.AUTO_PROJECTIVE, null);
        } else {
          android.util.Log.d(
              TAG,
              LP
                  + "performCrop: aspect="
                  + sel
                  + " → FIXED_RATIO short/long="
                  + String.format(java.util.Locale.US, "%.4f", ratio)
                  + (dewarpModel != null ? " (dewarp)" : ""));
          croppedBitmap =
              dewarpModel != null
                  ? OpenCVUtils.applyDewarp(
                      fullResSource, dewarpModel, OpenCVUtils.WarpMode.FIXED_RATIO, ratio)
                  : OpenCVUtils.applyPerspectiveCorrection(
                      fullResSource, cornersForSource, OpenCVUtils.WarpMode.FIXED_RATIO, ratio);
        }
      }
    }
    long dt = android.os.SystemClock.uptimeMillis() - t0;
    if (croppedBitmap != null) {
      android.util.Log.d(
          TAG,
          LP
              + "performCrop: cropped size="
              + croppedBitmap.getWidth()
              + "x"
              + croppedBitmap.getHeight()
              + ", took="
              + dt
              + "ms");
      // Post-warp safety net: trim residual non-paper border (dark background remnants left
      // when the crop trapezoid was slightly looser than the actual paper edges). This protects
      // downstream OCR from artefacts caused by black borders along the page.
      // TODO: Move to OCRFragement and OCRReviewFragment
      /*try {
        Bitmap trimmed = OpenCVUtils.trimNonPaperBorder(croppedBitmap);
        if (trimmed != null && trimmed != croppedBitmap) {
          android.util.Log.d(
              TAG,
              LP
                  + "performCrop: post-warp border trim "
                  + croppedBitmap.getWidth()
                  + "x"
                  + croppedBitmap.getHeight()
                  + " → "
                  + trimmed.getWidth()
                  + "x"
                  + trimmed.getHeight());
          croppedBitmap = trimmed;
        }
      } catch (Throwable t) {
        android.util.Log.w(TAG, LP + "performCrop: trimNonPaperBorder failed: " + t.getMessage());
      }*/
      // Hide/stop overlay to avoid further edge detection while we navigate away
      try {
        binding.trapezoidSelection.setVisibility(View.GONE);
        binding.trapezoidSelection.setImageBitmap(null);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      // Prevent the imageBitmap observer from re-initializing edge detection for this write-back
      skipNextBitmapObserver = true;
      // Persist the accepted corners (in full-res source coords) and the user rotation that was
      // baked into that source. This enables Re-Edit from the Export screen (FR #72): a later
      // re-entry into CropFragment can restore exactly the same trapezoid by setting the same
      // user rotation and applying these corners to the rebuilt full-res source.
      try {
        android.graphics.PointF[] accepted = new android.graphics.PointF[cornersForSource.length];
        for (int i = 0; i < cornersForSource.length; i++) {
          accepted[i] =
              new android.graphics.PointF(
                  (float) cornersForSource[i].x, (float) cornersForSource[i].y);
        }
        Integer urVal = cropViewModel.getUserRotationDegrees().getValue();
        int urDeg = urVal == null ? 0 : urVal;
        cropViewModel.setLastAcceptedCornersOriginal(accepted);
        cropViewModel.setLastAcceptedUserRotationDeg(urDeg);
      } catch (Throwable t) {
        android.util.Log.w(TAG, LP + "performCrop: persisting accepted corners failed: " + t);
      }
      // Write cropped bitmap first, then mark as cropped so isImageCropped observer can navigate
      // using the new bitmap
      cropViewModel.setImageBitmap(croppedBitmap);
      cropViewModel.setImageCropped(true);
    } else {
      android.util.Log.w(
          TAG, LP + "performCrop: OpenCV returned null cropped bitmap (took=" + dt + "ms)");
      UIUtils.showToast(
          requireContext(),
          getString(R.string.error_displaying_image, "crop failed"),
          android.widget.Toast.LENGTH_SHORT);
    }
  }

  /**
   * Configures the UI for cropping mode by adjusting the visibility of relevant views and updating
   * instructional text for the user.
   *
   * <p>The method prepares the fragment for the initial cropping workflow by: - Hiding the cropped
   * image preview. - Displaying the image to be cropped. - Showing the trapezoid selection area, if
   * available. - Making the cropping button container visible. - Hiding the general button
   * container. - Setting an instructional message prompting the user to adjust the trapezoid
   * corners and proceed with cropping.
   */
  private void showCropMode() {
    binding.croppedImage.setVisibility(View.GONE);
    binding.imageToCrop.setVisibility(View.VISIBLE);
    binding.trapezoidSelection.setVisibility(View.VISIBLE);
    binding.cropButtonContainer.setVisibility(View.VISIBLE);
    binding.rotationButtonBar.setVisibility(View.VISIBLE);
    binding.cropAspectButton.setVisibility(View.VISIBLE);

    // Ensure the trapezoid overlay always renders above the bottom button container while remaining
    // non-clickable
    // (so taps still pass through to the buttons). This guards against OEM/CL quirks even if XML
    // elevation is ignored.
    try {
      ViewCompat.setElevation(binding.trapezoidSelection, 100f);
      ViewCompat.setTranslationZ(binding.trapezoidSelection, 100f);
      // Ensure draw order is on top even if ConstraintLayout reorders children
      binding.trapezoidSelection.bringToFront();
    } catch (Throwable ignored) {
      // Best-effort; failure is non-critical
    }

    // Ensure in crop mode the cropped_image (when later shown) would anchor to button_container to
    // avoid overlap
    // Ensure overlay hints avoid bottom controls
    binding.getRoot().post(this::updateTrapezoidHintInset);
    binding.textCrop.setText(
        R.string.adjust_the_trapezoid_corners_to_select_the_area_to_crop_then_tap_the_crop_button);
  }

  private void navigateAfterCrop() {
    if (!isAdded() || getView() == null) return;
    try {
      // FR #72 — Re-Edit: when the crop was opened from the Export overlay, do not advance
      // through OCR again; pop directly back to the Export screen. The OCR layer is
      // invalidated below so the export preview can trigger a re-OCR if needed.
      if (Boolean.TRUE.equals(cropViewModel.isCameFromExport().getValue())) {
        cropViewModel.setCameFromExport(false);
        // FR #72: capture and clear the re-edit index so subsequent crop flows are unaffected.
        // The local copy is still used below to update the correct session page.
        final int reEditIdx = cropViewModel.getReEditPageIndex();
        cropViewModel.setReEditPageIndex(-1);
        // Invalidate downstream OCR result so the new crop drives a fresh OCR pass when the
        // user requests one (or via background re-OCR in a future iteration).
        try {
          androidx.lifecycle.ViewModelProvider vmp =
              new androidx.lifecycle.ViewModelProvider(requireActivity());
          de.schliweb.makeacopy.ui.ocr.OCRViewModel ocrVm =
              vmp.get(de.schliweb.makeacopy.ui.ocr.OCRViewModel.class);
          if (ocrVm != null) {
            // Best-effort invalidation: clear the recognized words so the export pipeline
            // does not embed stale OCR for the freshly recropped page. Re-OCR in a future
            // iteration can run automatically; for now the user can trigger it from Export.
            ocrVm.setWords(null);
          }
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
        // FR #72 — Push the freshly cropped bitmap into ExportViewModel BEFORE popping back,
        // so the existing ExportFragment instance (whose observer was attached in onCreateView)
        // sees the new bitmap and re-renders the preview. Without this update the observer
        // would still hold the previous documentBitmap reference and the preview would not
        // refresh when popping back to Export.
        try {
          Bitmap newCropped = cropViewModel.getImageBitmap().getValue();
          if (newCropped != null) {
            int userDeg = 0;
            Integer urv = cropViewModel.getUserRotationDegrees().getValue();
            if (urv != null) userDeg = ((urv % 360) + 360) % 360;
            Bitmap toSet = newCropped;
            if (userDeg != 0) {
              try {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(userDeg);
                Bitmap rotated =
                    Bitmap.createBitmap(
                        newCropped, 0, 0, newCropped.getWidth(), newCropped.getHeight(), m, true);
                if (rotated != null) toSet = rotated;
              } catch (Throwable ignore) {
                // Fall through with the crop result; Export will keep the fresh Re-Edit identity.
              }
            }
            androidx.lifecycle.ViewModelProvider vmp2 =
                new androidx.lifecycle.ViewModelProvider(requireActivity());
            de.schliweb.makeacopy.ui.export.ExportViewModel exportVm =
                vmp2.get(de.schliweb.makeacopy.ui.export.ExportViewModel.class);
            if (exportVm != null) {
              // FR #72 V1.3: mark this bitmap as the freshly re-edited page so the Edit
              // overlay only re-appears for THIS page (not for older pages selected via
              // the filmstrip).
              try {
                cropViewModel.setLastFreshReEditPageBitmap(toSet);
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
              exportVm.setDocumentBitmap(toSet);
              exportVm.setDocumentReady(true);
            }
            // FR #72 — Also update the ExportSessionViewModel page so the pages observer
            // in ExportFragment does NOT overwrite our freshly pushed documentBitmap with
            // the stale page bitmap. Without this, the observer logic
            //   if (curPreview not in pages[i].inMemoryBitmap) → loadPreview(pages[0])
            // restores the *old* cropped bitmap and the preview never refreshes.
            // V1 hot-workflow: replace the first (and typically only) page with a new
            // CompletedScan that carries the fresh bitmap. Multi-page Re-Edit per
            // specific page is a follow-up issue.
            try {
              de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel sessionVm =
                  vmp2.get(de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel.class);
              if (sessionVm != null) {
                java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages =
                    sessionVm.getPages().getValue();
                if (pages != null && !pages.isEmpty()) {
                  // FR #72 multi-page: use the index recorded by ExportFragment when the user
                  // tapped the Edit overlay, so the correct session page is replaced (not
                  // hardcoded index 0). Fall back to 0 only when the index is unknown
                  // (single-page hot workflow without a session match).
                  int idx = reEditIdx;
                  if (idx < 0 || idx >= pages.size()) idx = 0;
                  de.schliweb.makeacopy.ui.export.session.CompletedScan old = pages.get(idx);
                  if (old != null) {
                    de.schliweb.makeacopy.ui.export.session.CompletedScan replacement =
                        new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                            old.id(),
                            null,
                            0,
                            null,
                            null,
                            null,
                            old.createdAt(),
                            toSet.getWidth(),
                            toSet.getHeight(),
                            toSet,
                            old.schemaVersion(),
                            old.orientationMode());
                    sessionVm.updateAt(idx, replacement);
                    android.util.Log.d(
                        TAG,
                        "[FR72] Re-Edit: replaced session page "
                            + idx
                            + " with fresh bitmap "
                            + toSet.getWidth()
                            + "x"
                            + toSet.getHeight());
                  }
                }
              }
            } catch (Throwable t) {
              android.util.Log.w(
                  TAG, "[FR72] Re-Edit: updating ExportSessionViewModel failed: " + t);
            }
          }
        } catch (Throwable t) {
          android.util.Log.w(
              TAG, "[FR72] Re-Edit: pushing new bitmap to ExportViewModel failed: " + t);
        }
        boolean popped =
            Navigation.findNavController(requireView()).popBackStack(R.id.navigation_export, false);
        if (!popped) {
          // Fallback if Export is not on the back-stack: navigate forward.
          Navigation.findNavController(requireView()).navigate(R.id.navigation_export);
        }
        return;
      }
      android.content.SharedPreferences prefs =
          requireContext()
              .getSharedPreferences("export_options", android.content.Context.MODE_PRIVATE);
      boolean skipOcr = prefs.getBoolean("skip_ocr", false);
      int dest = skipOcr ? R.id.navigation_export : R.id.navigation_ocr;
      NavOptions.Builder navOptionsBuilder = new NavOptions.Builder().setLaunchSingleTop(true);
      if (skipOcr) {
        navOptionsBuilder.setPopUpTo(R.id.navigation_camera, false);
      }
      NavOptions navOptions = navOptionsBuilder.build();
      Navigation.findNavController(requireView()).navigate(dest, null, navOptions);
    } catch (Throwable ignored) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Order of {@link CropAspectRatio} values that are shown in the chooser dialog. The order is
   * deliberate (UX: most likely choices first) and decoupled from the enum's declaration order.
   */
  private static final CropAspectRatio[] ASPECT_SPINNER_ORDER =
      new CropAspectRatio[] {
        CropAspectRatio.AUTO,
        CropAspectRatio.ORIGINAL,
        CropAspectRatio.A4,
        CropAspectRatio.A5,
        CropAspectRatio.A3,
        CropAspectRatio.US_LETTER,
        CropAspectRatio.LEGAL,
        CropAspectRatio.CUSTOM,
      };

  /**
   * Wires up the aspect-ratio icon button on the crop screen. The button opens an {@link
   * AlertDialog} with single-choice options (built from {@link #ASPECT_SPINNER_ORDER}); selecting
   * {@link CropAspectRatio#CUSTOM} chains into the custom-ratio dialog. The currently persisted
   * choice (via {@link CropPrefsHelper#getLastAspect(android.content.Context)}) is pre-selected.
   */
  private void setupAspectButton() {
    binding.cropAspectButton.setOnClickListener(v -> showAspectChooserDialog());
  }

  /**
   * Wires up the Snap-to-Right-Angle toggle button. The visible state mirrors {@link
   * FeatureFlags#isCropSnapRightAngleEnabled()}; clicking flips the persisted preference via {@link
   * CropPrefsHelper#setSnapRightAngleEnabled(android.content.Context, boolean)} and updates the
   * in-memory cache via {@link FeatureFlags#setCropSnapRightAngleEnabled(boolean)}. The button uses
   * {@link View#setSelected(boolean)} for visual feedback (tinted accent when active) and announces
   * the new state for accessibility. The trapezoid view is invalidated so any active snap highlight
   * disappears when the user turns the assist off mid-drag.
   */
  private void setupSnapToggleButton() {
    if (binding == null) return;
    // Hydrate the in-memory cache from persistent storage so the View can read it Context-free.
    boolean persisted = CropPrefsHelper.getSnapRightAngleEnabled(requireContext());
    FeatureFlags.setCropSnapRightAngleEnabled(persisted);
    applySnapToggleVisuals(persisted);
    binding.cropSnapToggleButton.setOnClickListener(
        v -> {
          boolean newState = !FeatureFlags.isCropSnapRightAngleEnabled();
          FeatureFlags.setCropSnapRightAngleEnabled(newState);
          CropPrefsHelper.setSnapRightAngleEnabled(requireContext(), newState);
          applySnapToggleVisuals(newState);
          try {
            binding.trapezoidSelection.invalidate();
          } catch (Throwable ignore) {
            // Best-effort; failure is non-critical
          }
          String msg =
              getString(newState ? R.string.crop_snap_toggle_on : R.string.crop_snap_toggle_off);
          try {
            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT)
                .show();
          } catch (Throwable ignore) {
            // Best-effort user-visible message
          }
          try {
            de.schliweb.makeacopy.utils.ui.A11yUtils.announce(v, msg);
          } catch (Throwable ignore) {
            // Best-effort accessibility announcement
          }
        });
  }

  /**
   * Wires up the Curved-edges toggle button (Issue #91). Clicking flips the curved mode of the
   * trapezoid selection via {@link TrapezoidSelectionView#setCurvedMode(boolean)}. The button uses
   * {@link View#setSelected(boolean)} plus a tint change for visual feedback (same pattern as the
   * Snap-to-Right-Angle toggle) and announces the new state for accessibility. The mode is
   * intentionally not persisted in this MVP; it resets when the fragment is recreated.
   */
  private void setupCurvedToggleButton() {
    if (binding == null) return;
    applyCurvedToggleVisuals(binding.trapezoidSelection.isCurvedMode());
    binding.cropCurvedToggleButton.setOnClickListener(
        v -> {
          boolean newState = !binding.trapezoidSelection.isCurvedMode();
          binding.trapezoidSelection.setCurvedMode(newState);
          applyCurvedToggleVisuals(newState);
          // Issue #91 (Phase 3): the depth slider is only meaningful in curved mode; reset it
          // to neutral whenever the mode is toggled and show/hide it accordingly.
          dewarpDepth = 0.0;
          binding.cropDepthSlider.setProgress(DEPTH_SLIDER_NEUTRAL);
          binding.trapezoidSelection.setDepthPreview(0.0);
          estimatedTopEdgeProfile = null;
          estimatedBottomEdgeProfile = null;
          showDepthSlider(newState);
          if (newState) {
            // Issue #91 (Phase 2): kick off the automatic initial curve estimation in the
            // background; the result is applied only if curved mode is still active.
            startCurveEstimation();
          }
          String msg =
              getString(newState ? R.string.crop_curved_mode_on : R.string.crop_curved_mode_off);
          try {
            android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT)
                .show();
          } catch (Throwable ignore) {
            // Best-effort user-visible message
          }
          try {
            de.schliweb.makeacopy.utils.ui.A11yUtils.announce(v, msg);
          } catch (Throwable ignore) {
            // Best-effort accessibility announcement
          }
        });
  }

  /** Neutral (center) progress value of the depth slider (Issue #91, Phase 3). */
  private static final int DEPTH_SLIDER_NEUTRAL = 100;

  /**
   * Current perspective-depth fine adjustment in {@code [-1, 1]} (Issue #91, Phase 3); {@code 0} =
   * neutral. Applied to the {@link de.schliweb.makeacopy.utils.image.DewarpModel} via {@code
   * withDepth} in {@link #performCrop()} when curved mode is active. Intentionally not persisted
   * (MVP); resets when the fragment is recreated or the curved mode is toggled.
   */
  private double dewarpDepth = 0.0;

  /**
   * Chord-normalized offset profile of the top page edge from the last successful automatic curve
   * estimation (issue #91 follow-up: corner accuracy), or {@code null}. Consumed in {@link
   * #performCrop()} via {@link de.schliweb.makeacopy.utils.image.DewarpModel#withEdgeProfiles};
   * reset whenever the curved mode is toggled or a new estimation run starts.
   */
  private double[] estimatedTopEdgeProfile;

  /** Bottom-edge counterpart of {@link #estimatedTopEdgeProfile}. */
  private double[] estimatedBottomEdgeProfile;

  /**
   * Wires up the perspective-depth slider (Issue #91, Phase 3). The slider maps its progress range
   * {@code [0, 200]} linearly to a depth value in {@code [-1, 1]} (center = neutral). It is only
   * visible while curved mode is active; the value is consumed in {@link #performCrop()}.
   *
   * <p>Issue #91 (Phase 4): while the user drags the thumb, {@code image_to_crop} is temporarily
   * replaced by a live low-cost dewarp render of the currently displayed (not full-res) bitmap, so
   * the user can see the actual effect instead of only the abstract grid overlay. The trapezoid
   * overlay is hidden for the duration of the drag (its corner handles are meaningless on top of
   * an already-warped image) and restored, together with the original bitmap, on release.
   */
  private void setupDepthSlider() {
    if (binding == null) return;
    showDepthSlider(binding.trapezoidSelection.isCurvedMode());
    binding.cropDepthSlider.setOnSeekBarChangeListener(
        new android.widget.SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(
              android.widget.SeekBar seekBar, int progress, boolean fromUser) {
            dewarpDepth = (progress - DEPTH_SLIDER_NEUTRAL) / (double) DEPTH_SLIDER_NEUTRAL;
            // Live preview: mirror the depth into the overlay so its grid lines shift
            // towards the top/bottom curve while the slider is dragged.
            if (binding != null) binding.trapezoidSelection.setDepthPreview(dewarpDepth);
            if (fromUser) scheduleDepthPreviewRender();
          }

          @Override
          public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            // Reset any active Pan/Zoom first: the live preview swaps in a bitmap of a
            // different size/shape than the one the current zoom matrix was computed for, so a
            // stale zoom would render it distorted or cut off. See resetViewTransform() javadoc.
            if (binding != null) binding.trapezoidSelection.resetViewTransform();
            // Hide the corner/curve overlay for the duration of the drag; it is about to be
            // replaced by a real dewarped render of the image underneath.
            if (binding != null) binding.trapezoidSelection.setVisibility(View.INVISIBLE);
          }

          @Override
          public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
            android.util.Log.d(TAG, "setupDepthSlider: depth=" + dewarpDepth);
            restoreDepthPreviewImage();
            if (binding != null) {
              binding.trapezoidSelection.setVisibility(View.VISIBLE);
              binding.trapezoidSelection.setDepthPreview(dewarpDepth);
              binding.trapezoidSelection.invalidate();
            }
          }
        });
  }

  /** Debounce interval for {@link #scheduleDepthPreviewRender()} (Issue #91, Phase 4). */
  private static final long DEPTH_PREVIEW_DEBOUNCE_MS = 80;

  /**
   * Generation counter guarding the live depth-preview renders (Issue #91, Phase 4). Incremented
   * whenever a new render is scheduled or the drag ends, so a slow background render that finishes
   * after a newer one (or after the user released the slider) is discarded instead of overwriting
   * fresher state.
   */
  private final java.util.concurrent.atomic.AtomicInteger depthPreviewRenderGeneration =
      new java.util.concurrent.atomic.AtomicInteger(0);

  /** Pending debounced depth-preview render callback, or {@code null} if none is scheduled. */
  private Runnable pendingDepthPreviewRender;

  /**
   * Bitmap currently shown in {@code image_to_crop} as a live depth-preview render (Issue #91,
   * Phase 4), or {@code null} while the normal (un-warped) displayed bitmap is shown. Tracked so it
   * can be recycled once superseded by a newer render or once the drag ends.
   */
  private Bitmap activeDepthPreviewBitmap;

  /**
   * Debounces {@link #renderDepthPreview()} calls while the depth slider is being dragged (Issue
   * #91, Phase 4), so a fast finger swipe does not spawn a render thread per pixel of movement.
   */
  private void scheduleDepthPreviewRender() {
    if (binding == null) return;
    if (pendingDepthPreviewRender != null) {
      binding.trapezoidSelection.removeCallbacks(pendingDepthPreviewRender);
    }
    pendingDepthPreviewRender = this::renderDepthPreview;
    binding.trapezoidSelection.postDelayed(pendingDepthPreviewRender, DEPTH_PREVIEW_DEBOUNCE_MS);
  }

  /**
   * Renders a live preview of the current depth adjustment (Issue #91, Phase 4) by applying {@link
   * de.schliweb.makeacopy.utils.image.OpenCVUtils#applyDewarp} to the currently displayed
   * (screen-resolution, not full-res) bitmap on a background thread, then swaps it into {@code
   * image_to_crop} on the main thread. Mirrors the corner/midpoint transform used by {@link
   * #performCrop()}, but skips the full-res scaling step since the source here already is the
   * displayed bitmap. Silently does nothing if curved mode is off, the corners/midpoints are
   * invalid, or the render turns out stale by the time it completes.
   */
  private void renderDepthPreview() {
    pendingDepthPreviewRender = null;
    if (binding == null || !binding.trapezoidSelection.isCurvedMode()) return;
    final Bitmap displayedBitmap = cropViewModel.getImageBitmap().getValue();
    if (displayedBitmap == null || displayedBitmap.isRecycled()) return;

    final org.opencv.core.Point[] imgCorners;
    final org.opencv.core.Point[] midsDisplay;
    try {
      org.opencv.core.Point[] viewCorners = binding.trapezoidSelection.getCorners();
      imgCorners =
          CoordinateTransformUtils.transformViewToImageCoordinates(
              viewCorners, displayedBitmap, binding.imageToCrop);
      org.opencv.core.Point[] midsView = binding.trapezoidSelection.getCurveMidpointsViewCoords();
      midsDisplay =
          CoordinateTransformUtils.transformViewToImageCoordinates(
              midsView, displayedBitmap, binding.imageToCrop);
    } catch (Throwable t) {
      android.util.Log.w(TAG, "renderDepthPreview: corner transform failed: " + t);
      return;
    }
    if (!TrapezoidSelectionView.isValidImageQuad(
            imgCorners, displayedBitmap.getWidth(), displayedBitmap.getHeight())
        || midsDisplay == null
        || midsDisplay.length != 2
        || midsDisplay[0] == null
        || midsDisplay[1] == null) {
      android.util.Log.d(TAG, "renderDepthPreview: invalid corners/midpoints; skipping");
      return;
    }

    final org.opencv.core.Point topMid = midsDisplay[0];
    final org.opencv.core.Point bottomMid = midsDisplay[1];
    final double depth = dewarpDepth;
    final double[] topProfile = estimatedTopEdgeProfile;
    final double[] bottomProfile = estimatedBottomEdgeProfile;
    final int generation = depthPreviewRenderGeneration.incrementAndGet();
    final View postTarget = binding.trapezoidSelection;

    new Thread(
            () -> {
              Bitmap preview = null;
              try {
                if (!OpenCVUtils.isInitialized()) OpenCVUtils.init(requireContext());
                de.schliweb.makeacopy.utils.image.DewarpModel model =
                    de.schliweb.makeacopy.utils.image.DewarpModel.fromOnCurveMidpoints(
                        imgCorners, topMid, bottomMid);
                if (model != null) {
                  if (topProfile != null || bottomProfile != null) {
                    model = model.withEdgeProfiles(topProfile, bottomProfile);
                  }
                  model = model.withDepth(depth);
                  preview =
                      OpenCVUtils.applyDewarp(
                          displayedBitmap, model, OpenCVUtils.WarpMode.AUTO_PROJECTIVE, null);
                }
              } catch (Throwable t) {
                android.util.Log.w(TAG, "renderDepthPreview: render failed: " + t);
              }
              final Bitmap result = preview;
              postTarget.post(() -> applyDepthPreviewResult(generation, result, displayedBitmap));
            },
            "DepthPreviewRender")
        .start();
  }

  /**
   * Applies (or discards) the result of a background {@link #renderDepthPreview()} run (Issue #91,
   * Phase 4). Discards results from a stale generation (a newer render was scheduled, or the drag
   * already ended) as well as a {@code null}/unchanged result, recycling any bitmap that will not
   * end up on screen.
   */
  private void applyDepthPreviewResult(int generation, Bitmap result, Bitmap displayedBitmap) {
    if (binding == null || generation != depthPreviewRenderGeneration.get()) {
      if (result != null && result != displayedBitmap && !result.isRecycled()) result.recycle();
      return;
    }
    if (result == null || result == displayedBitmap) return;
    binding.imageToCrop.setImageBitmap(result);
    Bitmap previous = activeDepthPreviewBitmap;
    activeDepthPreviewBitmap = result;
    if (previous != null && previous != displayedBitmap && !previous.isRecycled()) {
      previous.recycle();
    }
  }

  /**
   * Restores {@code image_to_crop} to the normal displayed bitmap and cancels/invalidates any
   * in-flight depth-preview render (Issue #91, Phase 4). Called when the depth-slider drag ends.
   */
  private void restoreDepthPreviewImage() {
    if (binding == null) return;
    depthPreviewRenderGeneration.incrementAndGet();
    if (pendingDepthPreviewRender != null) {
      binding.trapezoidSelection.removeCallbacks(pendingDepthPreviewRender);
      pendingDepthPreviewRender = null;
    }
    Bitmap displayedBitmap = cropViewModel.getImageBitmap().getValue();
    if (displayedBitmap != null) binding.imageToCrop.setImageBitmap(displayedBitmap);
    if (activeDepthPreviewBitmap != null) {
      Bitmap previous = activeDepthPreviewBitmap;
      activeDepthPreviewBitmap = null;
      if (previous != displayedBitmap && !previous.isRecycled()) previous.recycle();
    }
  }

  /**
   * Shows or hides the depth slider (Issue #91, Phase 3). The layout keeps the slider outside the
   * image area (the preview's bottom is constrained to the slider's top), so the
   * {@link TrapezoidSelectionView} overlay never overlaps it and cannot consume its touch events
   * (which would pan the preview instead of moving the slider thumb). Raising elevation/Z here is
   * kept as an additional safety net.
   *
   * @param visible whether the slider should be visible
   */
  private void showDepthSlider(boolean visible) {
    if (binding == null) return;
    binding.cropDepthSlider.setVisibility(visible ? View.VISIBLE : View.GONE);
    if (visible) {
      try {
        ViewCompat.setElevation(binding.cropDepthSlider, 200f);
        ViewCompat.setTranslationZ(binding.cropDepthSlider, 200f);
        binding.cropDepthSlider.bringToFront();
      } catch (Throwable ignored) {
        // Best-effort; failure is non-critical
      }
    }
  }

  /**
   * Generation counter guarding against overlapping curve-estimation runs (Issue #91, Phase 2).
   * Incremented on every {@link #startCurveEstimation()} call; a background run applies its result
   * only if the counter still matches, so quickly toggling the curved mode on/off/on cannot apply a
   * stale estimate.
   */
  private final java.util.concurrent.atomic.AtomicInteger curveEstimationGeneration =
      new java.util.concurrent.atomic.AtomicInteger(0);

  /**
   * Starts the automatic initial curve estimation (Issue #91, Phase 2) on a background thread. The
   * current view-space corners are transformed to displayed-image coordinates via {@link
   * CoordinateTransformUtils#transformViewToImageCoordinates}, validated with {@link
   * TrapezoidSelectionView#isValidImageQuad}, and passed to {@code
   * OpenCVUtils.estimateDewarpCurveOffsets}. The resulting offset fractions (same sign convention
   * as {@link TrapezoidSelectionView#setCurveOffsetFractions(double, double)}) are posted back to
   * the main thread and applied only if the binding is still alive, curved mode is still active and
   * no newer estimation run has been started. A {@code null} estimate leaves the handles straight
   * (log only, no toast).
   */
  private void startCurveEstimation() {
    if (binding == null) return;
    final int generation = curveEstimationGeneration.incrementAndGet();
    estimatedTopEdgeProfile = null;
    estimatedBottomEdgeProfile = null;
    final Bitmap displayedBitmap = cropViewModel.getImageBitmap().getValue();
    if (displayedBitmap == null || displayedBitmap.isRecycled()) {
      android.util.Log.w(TAG, "startCurveEstimation: no displayed bitmap available");
      return;
    }
    final org.opencv.core.Point[] imgCorners;
    try {
      org.opencv.core.Point[] viewCorners = binding.trapezoidSelection.getCorners();
      imgCorners =
          CoordinateTransformUtils.transformViewToImageCoordinates(
              viewCorners, displayedBitmap, binding.imageToCrop);
    } catch (Throwable t) {
      android.util.Log.w(TAG, "startCurveEstimation: corner transform failed: " + t);
      return;
    }
    if (!TrapezoidSelectionView.isValidImageQuad(
        imgCorners, displayedBitmap.getWidth(), displayedBitmap.getHeight())) {
      android.util.Log.w(TAG, "startCurveEstimation: invalid image-space quad; skipping");
      return;
    }
    final View postTarget = binding.trapezoidSelection;
    new Thread(
            () -> {
              double[][] profiles = null;
              try {
                profiles =
                    de.schliweb.makeacopy.utils.image.OpenCVUtils.estimateDewarpEdgeProfiles(
                        displayedBitmap, imgCorners);
              } catch (Throwable t) {
                android.util.Log.w(TAG, "startCurveEstimation: estimation failed: " + t);
              }
              if (profiles == null || profiles.length != 2) {
                android.util.Log.d(
                    TAG, "startCurveEstimation: no reliable estimate; leaving handles straight");
                return;
              }
              final double[] topProfile = profiles[0];
              final double[] bottomProfile = profiles[1];
              final double topFrac =
                  topProfile == null
                      ? 0
                      : de.schliweb.makeacopy.utils.image.DewarpModel.sampleProfile(
                          topProfile, 0.5);
              final double bottomFrac =
                  bottomProfile == null
                      ? 0
                      : de.schliweb.makeacopy.utils.image.DewarpModel.sampleProfile(
                          bottomProfile, 0.5);
              postTarget.post(
                  () -> {
                    try {
                      if (binding == null) return;
                      if (generation != curveEstimationGeneration.get()) {
                        android.util.Log.d(
                            TAG, "startCurveEstimation: stale generation; result discarded");
                        return;
                      }
                      if (!binding.trapezoidSelection.isCurvedMode()) {
                        android.util.Log.d(
                            TAG, "startCurveEstimation: curved mode no longer active; discarded");
                        return;
                      }
                      android.util.Log.d(
                          TAG,
                          "startCurveEstimation: applying estimate top="
                              + topFrac
                              + ", bottom="
                              + bottomFrac);
                      binding.trapezoidSelection.setCurveOffsetFractions(topFrac, bottomFrac);
                      // Keep the full traced edge shapes for performCrop (corner accuracy).
                      estimatedTopEdgeProfile = topProfile;
                      estimatedBottomEdgeProfile = bottomProfile;
                    } catch (Throwable t) {
                      android.util.Log.w(TAG, "startCurveEstimation: apply failed: " + t);
                    }
                  });
            },
            "CurveEstimation")
        .start();
  }

  private void applyCurvedToggleVisuals(boolean active) {
    if (binding == null) return;
    binding.cropCurvedToggleButton.setSelected(active);
    int tint;
    if (active) {
      android.util.TypedValue tvActive = new android.util.TypedValue();
      requireContext()
          .getTheme()
          .resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tvActive, true);
      tint =
          tvActive.resourceId != 0
              ? androidx.core.content.ContextCompat.getColor(requireContext(), tvActive.resourceId)
              : tvActive.data;
    } else {
      android.util.TypedValue tv = new android.util.TypedValue();
      requireContext().getTheme().resolveAttribute(android.R.attr.colorControlNormal, tv, true);
      tint =
          tv.resourceId != 0
              ? androidx.core.content.ContextCompat.getColor(requireContext(), tv.resourceId)
              : tv.data;
    }
    androidx.core.widget.ImageViewCompat.setImageTintList(
        binding.cropCurvedToggleButton, android.content.res.ColorStateList.valueOf(tint));
  }

  private void applySnapToggleVisuals(boolean active) {
    if (binding == null) return;
    binding.cropSnapToggleButton.setSelected(active);
    int tint;
    if (active) {
      // Dynamic-color aware: use the theme's primary color token instead of a static color.
      android.util.TypedValue tvActive = new android.util.TypedValue();
      requireContext()
          .getTheme()
          .resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tvActive, true);
      tint =
          tvActive.resourceId != 0
              ? androidx.core.content.ContextCompat.getColor(requireContext(), tvActive.resourceId)
              : tvActive.data;
    } else {
      android.util.TypedValue tv = new android.util.TypedValue();
      requireContext().getTheme().resolveAttribute(android.R.attr.colorControlNormal, tv, true);
      tint =
          tv.resourceId != 0
              ? androidx.core.content.ContextCompat.getColor(requireContext(), tv.resourceId)
              : tv.data;
    }
    androidx.core.widget.ImageViewCompat.setImageTintList(
        binding.cropSnapToggleButton, android.content.res.ColorStateList.valueOf(tint));
  }

  private void showAspectChooserDialog() {
    final CropAspectRatio prev = CropPrefsHelper.getLastAspect(requireContext());
    final String[] labels = buildAspectLabels();
    final int checked = indexOfAspect(prev);
    final int[] selectedIdx = new int[] {checked};
    AlertDialog dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.crop_aspect_label)
            .setSingleChoiceItems(labels, checked, (d, which) -> selectedIdx[0] = which)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                android.R.string.ok,
                (d, w) -> {
                  int pos = selectedIdx[0];
                  if (pos < 0 || pos >= ASPECT_SPINNER_ORDER.length) return;
                  CropAspectRatio chosen = ASPECT_SPINNER_ORDER[pos];
                  if (chosen == CropAspectRatio.CUSTOM) {
                    // Always open the custom dialog so values can be edited.
                    showCustomAspectDialog();
                    return;
                  }
                  if (chosen != prev) {
                    CropPrefsHelper.setLastAspect(requireContext(), chosen);
                    android.util.Log.d(TAG, "aspect chooser → " + chosen);
                  }
                })
            .create();
    dialog.setOnShowListener(
        d -> DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));
    dialog.show();
  }

  private String[] buildAspectLabels() {
    String[] out = new String[ASPECT_SPINNER_ORDER.length];
    for (int i = 0; i < ASPECT_SPINNER_ORDER.length; i++) {
      out[i] = labelFor(ASPECT_SPINNER_ORDER[i]);
    }
    return out;
  }

  private String labelFor(CropAspectRatio a) {
    switch (a) {
      case AUTO:
        return getString(R.string.crop_aspect_auto);
      case ORIGINAL:
        return getString(R.string.crop_aspect_original);
      case A3:
        return getString(R.string.crop_aspect_a3);
      case A4:
        return getString(R.string.crop_aspect_a4);
      case A5:
        return getString(R.string.crop_aspect_a5);
      case US_LETTER:
        return getString(R.string.crop_aspect_letter);
      case LEGAL:
        return getString(R.string.crop_aspect_legal);
      case CUSTOM:
        double[] wh = CropPrefsHelper.getCustomRatio(requireContext());
        if (wh != null) {
          return getString(
              R.string.crop_aspect_custom_with_ratio,
              formatRatioComponent(wh[0]),
              formatRatioComponent(wh[1]));
        }
        return getString(R.string.crop_aspect_custom);
      default:
        return a.name();
    }
  }

  private static String formatRatioComponent(double v) {
    if (v == Math.rint(v) && !Double.isInfinite(v)) {
      return Long.toString((long) v);
    }
    return String.format(java.util.Locale.US, "%.2f", v);
  }

  private static int indexOfAspect(CropAspectRatio a) {
    for (int i = 0; i < ASPECT_SPINNER_ORDER.length; i++) {
      if (ASPECT_SPINNER_ORDER[i] == a) return i;
    }
    return 0; // AUTO
  }

  /**
   * Shows the custom (w:h) ratio dialog. If the user confirms with valid values, persists both the
   * custom values and the {@link CropAspectRatio#CUSTOM} selection; otherwise the previously
   * persisted aspect remains unchanged.
   */
  private void showCustomAspectDialog() {
    View view =
        LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_crop_aspect_custom, null, false);
    EditText etW = view.findViewById(R.id.crop_aspect_custom_w);
    EditText etH = view.findViewById(R.id.crop_aspect_custom_h);
    TextView err = view.findViewById(R.id.crop_aspect_custom_error);

    double[] existing = CropPrefsHelper.getCustomRatio(requireContext());
    if (existing != null) {
      etW.setText(formatRatioComponent(existing[0]));
      etH.setText(formatRatioComponent(existing[1]));
    }

    AlertDialog dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.crop_aspect_custom_dialog_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(
                android.R.string.ok,
                (d, w) -> {
                  double[] parsed = parseCustom(etW, etH);
                  if (parsed == null
                      || !CropPrefsHelper.setCustomRatio(requireContext(), parsed[0], parsed[1])) {
                    return;
                  }
                  CropPrefsHelper.setLastAspect(requireContext(), CropAspectRatio.CUSTOM);
                })
            .create();

    TextWatcher watcher =
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {}

          @Override
          public void afterTextChanged(Editable s) {
            double[] parsed = parseCustom(etW, etH);
            boolean ok = parsed != null && CropPrefsHelper.isValidCustom(parsed[0], parsed[1]);
            err.setVisibility(ok ? View.GONE : View.VISIBLE);
            android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positive != null) positive.setEnabled(ok);
          }
        };
    etW.addTextChangedListener(watcher);
    etH.addTextChangedListener(watcher);

    dialog.setOnShowListener(
        d -> {
          // Improve button contrast in night mode (analog to other dialogs).
          DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext());
          // Trigger initial validation pass once the OK button exists.
          watcher.afterTextChanged(etW.getText());
        });
    dialog.show();
  }

  private static double[] parseCustom(EditText etW, EditText etH) {
    try {
      String sw = etW.getText() == null ? "" : etW.getText().toString().trim().replace(',', '.');
      String sh = etH.getText() == null ? "" : etH.getText().toString().trim().replace(',', '.');
      if (sw.isEmpty() || sh.isEmpty()) return null;
      double w = Double.parseDouble(sw);
      double h = Double.parseDouble(sh);
      return new double[] {w, h};
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private void setRotationButtonsEnabled(boolean enabled) {
    if (binding == null) return;
    binding.buttonRotateLeft.setEnabled(enabled);
    binding.buttonRotateLeft.setAlpha(enabled ? 1f : 0.5f);
    binding.buttonRotateRight.setEnabled(enabled);
    binding.buttonRotateRight.setAlpha(enabled ? 1f : 0.5f);
  }

  private void updateTrapezoidHintInset() {
    if (binding == null) return;
    int inset = 0;
    View bar = binding.rotationButtonBar;
    if (bar.getVisibility() == View.VISIBLE) {
      int h = bar.getHeight();
      int margins = 0;
      ViewGroup.LayoutParams lp = bar.getLayoutParams();
      if (lp instanceof ViewGroup.MarginLayoutParams mlp) {
        margins = mlp.bottomMargin + mlp.topMargin;
      }
      inset = h + margins;
    }
    // Add a little breathing room
    float density = binding.getRoot().getResources().getDisplayMetrics().density;
    inset += (int) (8 * density + 0.5f);
    try {
      binding.trapezoidSelection.setBottomUiInsetPx(inset);
    } catch (Throwable ignored) {
      // Best-effort; failure is non-critical
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    if (Boolean.TRUE.equals(cropViewModel.isImageLoaded().getValue())
        && Boolean.FALSE.equals(cropViewModel.isImageCropped().getValue())) {
      showCropMode();
      Bitmap bitmap = cropViewModel.getImageBitmap().getValue();
      if (bitmap != null) {
        Bitmap safe = BitmapUtils.ensureDisplaySafe(bitmap);
        binding.trapezoidSelection.setImageBitmap(safe);
      }
      // Re-apply inset in case system UI or rotations changed sizes
      binding.getRoot().post(this::updateTrapezoidHintInset);
    }
  }

  @Override
  public void onDestroyView() {
    try {
      if (binding != null && binding.cropDevOverlay != null) {
        binding.cropDevOverlay.setModelRect(null);
        binding.cropDevOverlay.setDebugText(null);
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    try {
      // Issue #91 (Phase 4): invalidate any in-flight depth-preview render and release its
      // bitmap; the view it would post back to is about to be destroyed anyway.
      depthPreviewRenderGeneration.incrementAndGet();
      if (pendingDepthPreviewRender != null && binding != null) {
        binding.trapezoidSelection.removeCallbacks(pendingDepthPreviewRender);
      }
      pendingDepthPreviewRender = null;
      if (activeDepthPreviewBitmap != null) {
        if (!activeDepthPreviewBitmap.isRecycled()) activeDepthPreviewBitmap.recycle();
        activeDepthPreviewBitmap = null;
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    super.onDestroyView();
    binding = null;
  }

  // --- Dev overlay (framing logging) in the CropFragment ---
  private void updateDevOverlayForBitmap(@androidx.annotation.NonNull Bitmap bmp) {
    if (binding == null) return;
    if (!FeatureFlags.isFramingLoggingEnabled()) return;
    try {
      RectF fb = OpenCVUtils.getFallbackRectF(bmp.getWidth(), bmp.getHeight());
      RectF vr = mapBitmapToOverlayRect(fb, bmp.getWidth(), bmp.getHeight());
      if (vr != null) {
        binding.cropDevOverlay.setModelRect(vr);
        // In the crop screen, marking the model frame is sufficient
        binding.cropDevOverlay.setDebugText(null);
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  private RectF mapBitmapToOverlayRect(RectF src, int bmpW, int bmpH) {
    if (binding == null || src == null) return null;
    int vw = binding.cropDevOverlay.getWidth();
    int vh = binding.cropDevOverlay.getHeight();
    if (vw <= 0 || vh <= 0 || bmpW <= 0 || bmpH <= 0) return null;
    float sx = vw / (float) bmpW;
    float sy = vh / (float) bmpH;
    float scale = Math.min(sx, sy);
    float contentW = bmpW * scale;
    float contentH = bmpH * scale;
    float offX = (vw - contentW) * 0.5f;
    float offY = (vh - contentH) * 0.5f;
    return new RectF(
        offX + src.left * scale,
        offY + src.top * scale,
        offX + src.right * scale,
        offY + src.bottom * scale);
  }
}
