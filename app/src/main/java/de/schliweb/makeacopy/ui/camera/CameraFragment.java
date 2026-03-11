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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
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
import androidx.navigation.Navigation;
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
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  private boolean isFlashlightOn = false;
  private boolean hasFlash = false;
  // Live corner preview (document trapezoid)
  private ImageAnalysis imageAnalysis;
  private ExecutorService analysisExecutor;
  private volatile boolean analysisEnabled = false;
  private long lastAnalysisTs = 0L;
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
  private volatile long lastGuidanceEventTs = 0L;

  // Model-free distance estimation via autofocus (Camera2 API)
  // Value in diopters (1/meters). 0 or negative means unavailable.
  @SuppressWarnings("UnusedVariable") // explicit init required for volatile field
  private volatile float lastFocusDistanceDiopters = 0f;

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
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
              if (result == null || result.getData() == null) return;
              if (result.getResultCode() != android.app.Activity.RESULT_OK) return;
              Intent data = result.getData();
              java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
              if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                  Uri u = data.getClipData().getItemAt(i).getUri();
                  if (u != null) uris.add(u);
                }
              } else {
                Uri u = data.getData();
                if (u != null) uris.add(u);
              }
              if (uris.isEmpty()) return;

              java.util.List<Uri> imageUris = null;
              if (uris.size() > 1) {
                imageUris = new java.util.ArrayList<>();
                for (Uri u : uris) {
                  String m = requireContext().getContentResolver().getType(u);
                  if (m != null && m.startsWith("image/")) imageUris.add(u);
                }
                if (imageUris.size() > 20) {
                  imageUris = new java.util.ArrayList<>(imageUris.subList(0, 20));
                }
                if (imageUris.size() <= 1) imageUris = null;
              }

              if (imageUris == null) {
                Uri uri = uris.get(0);
                // Determine MIME type
                String mime = requireContext().getContentResolver().getType(uri);
                if (mime == null) {
                  UIUtils.showToast(
                      requireContext(), R.string.error_unknown_file_type, Toast.LENGTH_SHORT);
                  return;
                }
                // Persist read permission if granted by the chooser (ignore if not allowed)
                int takeFlags =
                    data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if ((takeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                  try {
                    requireContext()
                        .getContentResolver()
                        .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                  } catch (SecurityException se) {
                    Log.w(TAG, "Persistable read permission not granted by provider", se);
                  }
                }
                Uri folderHint =
                    de.schliweb.makeacopy.utils.infra.DocumentUriUtils.deriveParentDocumentUri(uri);
                ExportPrefsHelper.setLastImportUri(
                    requireContext(), folderHint != null ? folderHint.toString() : uri.toString());
                if (mime.startsWith("image/")) {
                  handleImageImport(uri);
                } else if (mime.equals("application/pdf")) {
                  handlePdfImport(uri);
                } else {
                  UIUtils.showToast(
                      requireContext(), R.string.error_unsupported_file_type, Toast.LENGTH_SHORT);
                }
                return;
              }

              // Multi-image path: decode on background thread, then navigate to Export
              final Context ctx = requireContext();
              final java.util.List<Uri> toDecode = new java.util.ArrayList<>(imageUris);
              ExecutorService loadExec = Executors.newFixedThreadPool(2);
              Handler mainHandler = new Handler(Looper.getMainLooper());
              loadExec.execute(
                  () -> {
                    java.util.ArrayList<de.schliweb.makeacopy.ui.export.session.CompletedScan>
                        decoded = new java.util.ArrayList<>();
                    for (Uri u : toDecode) {
                      Bitmap bmp =
                          de.schliweb.makeacopy.utils.image.ImageLoader.decode(ctx, null, u);
                      if (bmp != null) {
                        decoded.add(
                            new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                                java.util.UUID.randomUUID().toString(),
                                null,
                                0,
                                null,
                                null,
                                null,
                                System.currentTimeMillis(),
                                bmp.getWidth(),
                                bmp.getHeight(),
                                bmp,
                                1,
                                "metadata"));
                      }
                    }
                    mainHandler.post(
                        () -> {
                          if (!isAdded()) return;
                          if (decoded.isEmpty()) return;
                          de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel
                              exportSessionViewModel =
                                  new ViewModelProvider(requireActivity())
                                      .get(
                                          de.schliweb.makeacopy.ui.export.session
                                              .ExportSessionViewModel.class);
                          exportSessionViewModel.setInitial(decoded.get(0));
                          if (decoded.size() > 1) {
                            exportSessionViewModel.addAll(
                                decoded.subList(1, decoded.size()));
                          }
                          Uri firstUri = toDecode.get(0);
                          int takeFlags =
                              data.getFlags()
                                  & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                      | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                          if ((takeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                            try {
                              requireContext()
                                  .getContentResolver()
                                  .takePersistableUriPermission(
                                      firstUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (SecurityException se) {
                              Log.w(
                                  TAG,
                                  "Persistable read permission not granted by provider",
                                  se);
                            }
                          }
                          Uri folderHint =
                              de.schliweb.makeacopy.utils.infra.DocumentUriUtils
                                  .deriveParentDocumentUri(firstUri);
                          ExportPrefsHelper.setLastImportUri(
                              ctx,
                              folderHint != null ? folderHint.toString() : firstUri.toString());
                          try {
                            Navigation.findNavController(requireView())
                                .navigate(R.id.navigation_export);
                          } catch (IllegalArgumentException | IllegalStateException ignored) {
                            // Best-effort; failure is non-critical
                          }
                        });
                  });
            });

    binding = FragmentCameraBinding.inflate(inflater, container, false);
    View root = binding.getRoot();

    // Verbose environment log to help diagnose device-specific issues
    logEnvironment();

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

    // Init UI visibility
    showCameraMode();

    // DocQuad runner is now injected via Hilt (docQuadOrtRunner field).
    // No proactive loading needed — the singleton is created eagerly by the DI container.

    final TextView textView = binding.textCamera;
    cameraViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

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

    // Set up pick image button (supports images and PDFs)
    binding.buttonPickImage.setOnClickListener(
        v -> {
          Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
          intent.addCategory(Intent.CATEGORY_OPENABLE);
          // Accept both images and PDFs
          intent.setType("*/*");
          intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "application/pdf"});
          intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
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
        });

    // Options
    binding.buttonCameraOptions.setOnClickListener(
        v -> {
          getParentFragmentManager()
              .setFragmentResultListener(
                  CameraOptionsDialogFragment.REQUEST_KEY,
                  getViewLifecycleOwner(),
                  (requestKey, bundle) -> {
                    boolean skip =
                        bundle.getBoolean(CameraOptionsDialogFragment.BUNDLE_SKIP_OCR, false);
                    boolean analysisPref =
                        bundle.getBoolean(
                            CameraOptionsDialogFragment.BUNDLE_ANALYSIS_ENABLED, true);
                    boolean a11yPref =
                        bundle.getBoolean(
                            CameraOptionsDialogFragment.BUNDLE_ACCESSIBILITY_MODE, false);
                    boolean exposurePref =
                        bundle.getBoolean(
                            CameraOptionsDialogFragment.BUNDLE_EXPOSURE_COMPENSATION, false);
                    Context ctx = getContext();
                    if (ctx != null) {
                      android.content.SharedPreferences prefs =
                          ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
                      prefs
                          .edit()
                          .putBoolean("skip_ocr", skip)
                          .putBoolean("include_ocr", !skip)
                          .putBoolean("analysis_enabled", analysisPref)
                          // Accessibility is already persisted by the dialog; keep a mirror for
                          // local reads if needed
                          .putBoolean(
                              CameraOptionsDialogFragment.BUNDLE_ACCESSIBILITY_MODE, a11yPref)
                          .apply();
                    }
                    // Apply analysis toggle immediately if we are in camera mode
                    if (binding != null && binding.viewFinder.getVisibility() == View.VISIBLE) {
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
                    getParentFragmentManager()
                        .clearFragmentResultListener(CameraOptionsDialogFragment.REQUEST_KEY);
                  });
          CameraOptionsDialogFragment.show(getParentFragmentManager());
        });

    // Intercept hardware volume keys in Accessibility Mode to trigger shutter
    root.setFocusableInTouchMode(true);
    root.requestFocus();
    root.setOnKeyListener(
        (v, keyCode, event) -> {
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
          boolean cameraVisible =
              binding != null && binding.viewFinder.getVisibility() == View.VISIBLE;
          boolean ready = binding != null && binding.buttonScan.isEnabled();
          if (cameraVisible && ready) {
            captureImage();
          }
          return true; // consume to suppress actual volume change
        });

    // Library entry from Camera screen (feature-gated)
    if (FeatureFlags.isScanLibraryEnable()) {
      binding.buttonOpenLibraryCam.setVisibility(View.VISIBLE);
      binding.buttonOpenLibraryCam.setOnClickListener(
          v -> {
            try {
              Navigation.findNavController(requireView()).navigate(R.id.navigation_scans_library);
            } catch (IllegalArgumentException | IllegalStateException ex) {
              Log.w(TAG, "Navigation to scans library failed", ex);
            }
          });
    } else {
      binding.buttonOpenLibraryCam.setVisibility(View.GONE);
    }

    // Set up retake and confirm button listeners
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
          boolean skipOcr = false;
          boolean skipCropping = false;
          Context ctx2 = getContext();
          if (ctx2 != null) {
            android.content.SharedPreferences prefs =
                ctx2.getSharedPreferences("export_options", Context.MODE_PRIVATE);
            skipOcr = prefs.getBoolean("skip_ocr", false);
            skipCropping = prefs.getBoolean("skip_cropping", false);
          }
          int dest =
              skipCropping
                  ? (skipOcr ? R.id.navigation_export : R.id.navigation_ocr)
                  : R.id.navigation_crop;
          if (skipCropping && !skipOcr) {
            OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
            ocrVm.resetForNewImage();
          }
          try {
            Navigation.findNavController(requireView()).navigate(dest);
          } catch (IllegalArgumentException | IllegalStateException ignored) {
            // Best-effort; failure is non-critical
          }
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

    // Handle back: in review mode -> reset, otherwise default
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

    return root;
  }

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
            binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
            logCameraCapabilities();
            setupExposureCompensation();

            // Touch to focus
            binding.viewFinder.setOnTouchListener(
                (v, event) -> {
                  if (event.getAction() == MotionEvent.ACTION_UP && camera != null) {
                    MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
                    MeteringPoint pt = mpf.createPoint(event.getX(), event.getY());
                    FocusMeteringAction action =
                        new FocusMeteringAction.Builder(
                                pt,
                                FocusMeteringAction.FLAG_AF
                                    | FocusMeteringAction.FLAG_AE
                                    | FocusMeteringAction.FLAG_AWB)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build();
                    camera.getCameraControl().startFocusAndMetering(action);
                    v.performClick();
                    return true;
                  }
                  return false;
                });
            // Overlay tap → same focus
            binding.cornerOverlay.setOnTouchListener(
                (ov, ev) -> {
                  if (ev.getAction() == MotionEvent.ACTION_UP && camera != null) {
                    MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
                    MeteringPoint pt = mpf.createPoint(ev.getX(), ev.getY());
                    FocusMeteringAction a =
                        new FocusMeteringAction.Builder(
                                pt,
                                FocusMeteringAction.FLAG_AF
                                    | FocusMeteringAction.FLAG_AE
                                    | FocusMeteringAction.FLAG_AWB)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build();
                    camera.getCameraControl().startFocusAndMetering(a);
                    ov.performClick();
                    return true;
                  }
                  return false;
                });
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
    PreviewView.ImplementationMode implMode =
        isSony
            ? PreviewView.ImplementationMode.COMPATIBLE
            : (tier == BindTier.PERF
                ? PreviewView.ImplementationMode.PERFORMANCE
                : PreviewView.ImplementationMode.COMPATIBLE);
    binding.viewFinder.setImplementationMode(implMode);
    binding.viewFinder.setScaleType(PreviewView.ScaleType.FIT_CENTER);
    Log.i(TAG, "bindWithTier: implMode=" + implMode + ", scaleType=FIT_CENTER, isSony=" + isSony);

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

    // Some Sony devices reject sessions when an explicit AE_TARGET_FPS_RANGE is set.
    // To avoid "Unsupported set of inputs/outputs provided" (endConfigure) we skip forcing FPS on
    // Sony.
    if (!isSony) {
      android.util.Range<Integer> fps = new android.util.Range<>(15, 30);
      pExt.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
      cExt.setCaptureRequestOption(
          android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
    } else {
      Log.i(TAG, "bindWithTier: skipping AE_TARGET_FPS_RANGE on Sony device");
    }

    // AF continuous picture is generally safe and expected; keep for all OEMs
    pExt.setCaptureRequestOption(
        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
    cExt.setCaptureRequestOption(
        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
        android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

    if (!isSony) {
      // High-quality post-processing and ZSL toggles can cause session config failures on some Sony
      // devices.
      // Apply only on non-Sony devices.
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
      Log.i(TAG, "bindWithTier: skipping HQ NR/EDGE/CCA/SHADING and ZSL toggle on Sony device");
    }

    imageCapture = captureBuilder.build();
    preview = previewBuilder.build();

    // Analyzer
    setupOrUpdateImageAnalysis(rotation);

    CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

    try {
      cameraProvider.unbindAll();

      // Non-Sony: keep existing sequence Preview → Preview+Analysis → Preview+Analysis+Capture
      // 1) Preview only
      camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview);
      setPreviewSurfaceProviderWithLog(tier);
      logResolutions("Bind: Preview");

      // 2) Preview + Analysis
      try {
        cameraProvider.unbindAll();
        camera =
            cameraProvider.bindToLifecycle(
                getViewLifecycleOwner(), cameraSelector, preview, imageAnalysis);
        setPreviewSurfaceProviderWithLog(tier);
        logResolutions("Bind: Preview+Analysis");

        // 3) Preview + Analysis + Capture
        try {
          cameraProvider.unbindAll();
          camera =
              cameraProvider.bindToLifecycle(
                  getViewLifecycleOwner(), cameraSelector, preview, imageAnalysis, imageCapture);
          setPreviewSurfaceProviderWithLog(tier);
          logResolutions("Bind: Preview+Analysis+Capture");
        } catch (IllegalArgumentException e3) {
          Log.w(
              TAG,
              "Bind failed for Preview+Analysis+Capture on " + tier + " → fallback Preview+Capture",
              e3);
          cameraProvider.unbindAll();
          camera =
              cameraProvider.bindToLifecycle(
                  getViewLifecycleOwner(), cameraSelector, preview, imageCapture);
          setPreviewSurfaceProviderWithLog(tier);
          logResolutions("Fallback: Preview+Capture");
        }
      } catch (IllegalArgumentException e2) {
        Log.w(TAG, "Bind failed for Preview+Analysis on " + tier + " → fallback Preview only", e2);
        cameraProvider.unbindAll();
        camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview);
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

    } catch (IllegalArgumentException e) {
      Log.e(TAG, "bindToLifecycle failed: " + e.getMessage(), e);
      escalateBindTier();
    }
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

      // short AF/AE pre-focus
      MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
      MeteringPoint center =
          mpf.createPoint(binding.viewFinder.getWidth() / 2f, binding.viewFinder.getHeight() / 2f);

      // Reduced timeout from 3s to 1s for faster capture response
      FocusMeteringAction fma =
          new FocusMeteringAction.Builder(
                  center, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
              .setAutoCancelDuration(1, TimeUnit.SECONDS)
              .build();

      ListenableFuture<FocusMeteringResult> fut =
          camera.getCameraControl().startFocusAndMetering(fma);

      fut.addListener(
          () -> {
            try {
              FocusMeteringResult result =
                  fut.get(); // does not block because the listener is only invoked after completion
              boolean ok = result != null && result.isFocusSuccessful();
              Log.d(TAG, "captureImage: pre-focus result=" + ok);
            } catch (Exception e) {
              Log.w(TAG, "captureImage: pre-focus threw: " + e.getMessage());
            }
            doTakePicture(outputOptions, photoFile);
          },
          ContextCompat.getMainExecutor(requireContext()));

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

              OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
              ocrVm.resetForNewImage();

              cropViewModel.setImageCropped(false);
              cropViewModel.setImageBitmap(null);

              boolean skipOcr = false;
              boolean skipCropping = false;
              Context ctx = getContext();
              if (ctx != null) {
                android.content.SharedPreferences prefs =
                    ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
                skipOcr = prefs.getBoolean("skip_ocr", false);
                skipCropping = prefs.getBoolean("skip_cropping", false);
              }

              int dest =
                  skipCropping
                      ? (skipOcr ? R.id.navigation_export : R.id.navigation_ocr)
                      : R.id.navigation_crop;
              try {
                Navigation.findNavController(requireView()).navigate(dest);
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
      analysisExecutor.shutdownNow();
      analysisExecutor = null;
    }
    streamObserverAttached = false;
    binding = null;
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
          new AlertDialog.Builder(requireContext())
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

    // SeekBar: 0..max maps to lower..upper
    int seekMax = upper - lower;
    binding.exposureSlider.setMax(seekMax);

    // Restore persisted index, clamped to current device range
    android.content.SharedPreferences prefs =
        requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
    int savedIndex = prefs.getInt(PREF_EXPOSURE_INDEX, 0);
    int clampedIndex = ExposureCompensationHelper.clampIndex(savedIndex, lower, upper);

    binding.exposureSlider.setProgress(
        ExposureCompensationHelper.indexToProgress(clampedIndex, lower));
    updateExposureLabel(clampedIndex, step);
    applyExposureCompensation(clampedIndex);

    binding.exposureSlider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (!fromUser) return;
            int index = ExposureCompensationHelper.progressToIndex(progress, lower);
            updateExposureLabel(index, step);
          }

          @Override
          public void onStartTrackingTouch(SeekBar seekBar) {}

          @Override
          public void onStopTrackingTouch(SeekBar seekBar) {
            int index = ExposureCompensationHelper.progressToIndex(seekBar.getProgress(), lower);
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

    // Effective analysis: run analyzer whenever user enabled it OR Accessibility Mode is on
    boolean a11y = isAccessibilityModeEnabled();
    boolean effectiveAnalysis = enabled || a11y;

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
            }
          });

      imageAnalysis = analysisBuilder.build();
    } else {
      imageAnalysis.setTargetRotation(rotation);
    }

    // Analyzer should run when either user enabled visual preview or Accessibility Mode is on
    boolean a11y = isAccessibilityModeEnabled();
    boolean effectiveAnalysis = analysisEnabled || a11y;

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
    lastGuidanceEventTs = 0L;
  }

  private void clearA11yGuidanceController() {
    a11yGuidanceController = null;
    a11yStateMachine = null;
    lastGuidanceEventHint = null;
    lastGuidanceEventTs = 0L;
  }

  private void analyzeFrameForCorners(@NonNull ImageProxy image) {
    Bitmap bmp = null;
    try {
      // Note: Do NOT adapt exposure during live corner preview.
      // Running adaptive EC per analyzed frame caused progressive darkening when the torch is on.
      // We keep exposure stable here to ensure a consistent preview brightness.

      // Run analyzer if user enabled visual analysis OR A11y mode is active (internal analysis)
      boolean effectiveAnalysis = analysisEnabled || isAccessibilityModeEnabled();
      if (!effectiveAnalysis || binding == null || !isAdded()) return;

      long now = System.currentTimeMillis();
      // Orientation for this frame processing: initial defaults, set if available later
      int orientBucketLocal = -1; // 0 or 90
      double orientConfLocal = -1.0;
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
      // (bmp itself is not effectively-final anymore because we recycle it in finally.)
      final int bmpW = bmp.getWidth();
      final int bmpH = bmp.getHeight();

      // Init CV once
      if (!OpenCVUtils.isInitialized()) {
        try {
          OpenCVUtils.init(requireContext().getApplicationContext());
        } catch (Exception e) {
          Log.w(TAG, "OpenCV init failed", e);
        }
      }

      // DocQuad is the standard detector with OpenCV as fallback.
      // Cache the detector so DocQuad runner/throttle actually persist across frames.
      de.schliweb.makeacopy.ml.corners.CornerDetector liveDetector = cachedLiveCornerDetector;
      if (liveDetector == null || !cachedLiveCornerDetectorFlag) {
        liveDetector =
            de.schliweb.makeacopy.ml.corners.CornerDetectorFactory.forLive(
                requireContext(), docQuadOrtRunner);
        cachedLiveCornerDetector = liveDetector;
        cachedLiveCornerDetectorFlag = true;
      }

      org.opencv.core.Point[] pts;
      boolean hasValid;

      de.schliweb.makeacopy.ml.corners.DetectionResult r =
          liveDetector.detect(bmp, requireContext());
      if (r != null
          && r.success
          && r.cornersOriginalTLTRBRBL != null
          && r.cornersOriginalTLTRBRBL.length == 4) {
        pts = new org.opencv.core.Point[4];
        for (int i = 0; i < 4; i++) {
          pts[i] =
              new org.opencv.core.Point(
                  r.cornersOriginalTLTRBRBL[i][0], r.cornersOriginalTLTRBRBL[i][1]);
        }
        hasValid = true;
      } else {
        pts = null;
        hasValid = false;
      }
      // Live-DocQuad liefert aktuell keinen Score (Determinismus/Performance).

      // Map bitmap coords to overlay coords (PreviewView with FIT_CENTER) when valid
      android.graphics.PointF[] viewPts = hasValid ? mapToOverlayPoints(pts, bmpW, bmpH) : null;

      // Optional: Evaluate FramingEngine (logging and/or accessibility guidance)
      boolean wantFraming =
          FeatureFlags.isFramingLoggingEnabled()
              || (FeatureFlags.isA11yGuidanceEnabled() && isAccessibilityModeEnabled());
      FramingResult fr = null;
      android.graphics.RectF fbRectForOverlay = null;
      if (wantFraming) {
        try {
          android.graphics.PointF[] quad = null;
          if (hasValid) {
            quad = new android.graphics.PointF[4];
            for (int i = 0; i < 4; i++) {
              quad[i] = new android.graphics.PointF((float) pts[i].x, (float) pts[i].y);
            }
          }
          android.graphics.RectF fbRect = OpenCVUtils.getFallbackRectF(bmpW, bmpH);
          FramingEngine.Input feIn = new FramingEngine.Input(bmpW, bmpH, quad, fbRect);
          fr = new FramingEngine().evaluate(feIn);
          fbRectForOverlay = fbRect;
          if (FeatureFlags.isFramingLoggingEnabled()) {
            Log.d("Framing", "FramingResult=" + fr);
          }
          // Estimate orientation once (shared for A11y & overlay)
          try {
            OpenCVUtils.OrientationEstimate est = OpenCVUtils.estimateTextOrientation(bmp);
            orientBucketLocal = (est.bucketDeg() == 90) ? 90 : 0;
            orientConfLocal = est.confidence();
          } catch (Exception e) {
            Log.d(TAG, "estimateTextOrientation failed", e);
          }

          // A11y guidance using new state machine (concept-based implementation)
          // Ensure state machine exists so overlay can mirror cadence even without a screen reader
          if (a11yStateMachine == null) {
            initA11yGuidanceController();
          }
          if (a11yStateMachine != null) {
            // Convert detected points to PointF array for state machine
            android.graphics.PointF[] quadForA11y = null;
            if (hasValid && pts != null && pts.length == 4) {
              quadForA11y = new android.graphics.PointF[4];
              for (int i = 0; i < 4; i++) {
                quadForA11y[i] = new android.graphics.PointF((float) pts[i].x, (float) pts[i].y);
              }
            }

            // Optional: inject orientation hint into FramingResult if no document
            FramingResult frForA11y = fr;
            if (fr != null && !fr.hasDocument && orientConfLocal >= 0.30) {
              GuidanceHint oriHint =
                  (orientBucketLocal == 90)
                      ? GuidanceHint.ORIENTATION_LANDSCAPE_TIP
                      : GuidanceHint.ORIENTATION_PORTRAIT_TIP;
              frForA11y =
                  new FramingResult(
                      fr.quality,
                      fr.dxNorm,
                      fr.dyNorm,
                      fr.scaleRatio,
                      fr.tiltHorizontal,
                      fr.tiltVertical,
                      oriHint,
                      false);
            }

            // Process frame through state machine (with model-free focus distance)
            final FramingResult finalFrForA11y = frForA11y;
            final android.graphics.PointF[] finalQuadForA11y = quadForA11y;
            final float focusDist = lastFocusDistanceDiopters; // model-free distance signal
            a11yStateMachine.onFrame(
                finalQuadForA11y,
                bmpW,
                bmpH,
                finalFrForA11y,
                focusDist,
                now,
                (event, state) -> {
                  // Map event to GuidanceHint for compatibility
                  GuidanceHint hint = mapEventToHint(event);
                  if (hint == null) return;

                  // Update shared cadence state for overlay
                  lastGuidanceEventHint = hint;
                  lastGuidanceEventTs = now;

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
                });
          }
        } catch (Exception ignored) {
          // Best-effort; failure is non-critical
        }
      }

      // Removed: direct one‑shot announcement for orientation. Orientation now flows into the
      // central guidance path (see above), including hysteresis/rate limiting.

      // --- Jitter reduction & hysteresis for the corner preview ---
      if (hasValid && viewPts != null) {
        consecutiveValidFrames++;
        consecutiveInvalidFrames = 0;
        // Init filter
        if (lastFilteredCorners == null) {
          lastFilteredCorners = new android.graphics.PointF[4];
          for (int i = 0; i < 4; i++)
            lastFilteredCorners[i] = new android.graphics.PointF(viewPts[i].x, viewPts[i].y);
        } else {
          // Apply EMA to each coordinate
          for (int i = 0; i < 4; i++) {
            float fx = lastFilteredCorners[i].x;
            float fy = lastFilteredCorners[i].y;
            float nx = CORNER_EMA_ALPHA * viewPts[i].x + (1f - CORNER_EMA_ALPHA) * fx;
            float ny = CORNER_EMA_ALPHA * viewPts[i].y + (1f - CORNER_EMA_ALPHA) * fy;
            lastFilteredCorners[i].x = nx;
            lastFilteredCorners[i].y = ny;
          }
        }
      } else {
        consecutiveInvalidFrames++;
        consecutiveValidFrames = 0;
      }

      // Compute score EMA only when a value exists
      // Use FramingEngine quality (0..1) instead of det.score() which is always 0.0 for DocQuad
      double rawScore = (fr != null) ? fr.quality : 0.0;
      if (hasValid) {
        if (lastScoreEma < 0) lastScoreEma = rawScore;
        else lastScoreEma = SCORE_EMA_ALPHA * rawScore + (1.0 - SCORE_EMA_ALPHA) * lastScoreEma;
      }

      // UI update: overlay and score with hysteresis
      final FramingResult frUi = fr;
      final android.graphics.RectF fbRectUi = fbRectForOverlay;
      final int orientBucketForUi = orientBucketLocal;
      final double orientConfForUi = orientConfLocal;
      runOnUiThreadSafe(
          () -> {
            if (binding == null) return;

            // Show visible corner preview only when the user enabled visual analysis
            if (analysisEnabled) {
              boolean shouldShow =
                  (consecutiveValidFrames >= OVERLAY_SHOW_AFTER_VALID)
                      || binding.cornerOverlay.getVisibility() == View.VISIBLE;
              if (hasValid && lastFilteredCorners != null && shouldShow) {
                binding.cornerOverlay.setCorners(lastFilteredCorners);
              } else {
                // Only hide after enough consecutive invalid frames
                if (consecutiveInvalidFrames >= OVERLAY_HIDE_AFTER_INVALID) {
                  binding.cornerOverlay.setCorners(null);
                  lastFilteredCorners = null;
                }
              }
            } else {
              // Visual analysis off → do not draw corners
              binding.cornerOverlay.setCorners(null);
            }

            // Dev overlay: modelRect + metrics when logging flag is active
            if (FeatureFlags.isFramingLoggingEnabled() && frUi != null && fbRectUi != null) {
              android.graphics.RectF viewRect = mapToOverlayRect(fbRectUi, bmpW, bmpH);
              binding.cornerOverlay.setModelRect(viewRect);
              // Compact debug text in percentages, 1 decimal place
              // Orientation was already estimated above → just display here
              // IMPORTANT: Overlay hint must follow the same rhythm as the screen reader.
              // Therefore, we display the last hint emitted by the AccessibilityGuidanceController.
              GuidanceHint hintToShow = lastGuidanceEventHint;
              String dbg =
                  String.format(
                      Locale.US,
                      "q=%.2f\nΔx=%.2f Δy=%.2f\nscale=%.2f\ntiltH=%.2f tiltV=%.2f\nori=%s conf=%s\nhint=%s",
                      frUi.quality,
                      frUi.dxNorm,
                      frUi.dyNorm,
                      frUi.scaleRatio,
                      frUi.tiltHorizontal,
                      frUi.tiltVertical,
                      (orientBucketForUi >= 0 ? (orientBucketForUi + "°") : "-"),
                      (orientConfForUi >= 0
                          ? String.format(Locale.US, "%.2f", orientConfForUi)
                          : "-"),
                      hintToShow != null ? hintToShow.name() : "-");
              binding.cornerOverlay.setDebugText(dbg);
            } else {
              binding.cornerOverlay.setModelRect(null);
              binding.cornerOverlay.setDebugText(null);
            }
          });

      // Accessibility feedback when framing is stable and good
      if (isAccessibilityModeEnabled()) {
        // Use the smoothed score for stability logic when available
        lastScore = (lastScoreEma >= 0 ? lastScoreEma : rawScore);
        if (isStableFor(5, 0.8)) {
          maybeSignalGoodFraming();
        }
      } else {
        // Reset counters when mode is off
        lastScore = 0.0;
        stableCount = 0;
      }
    } catch (Exception e) {
      Log.w(TAG, "analyzeFrameForCorners failed: " + e.getMessage(), e);
    } finally {
      // Avoid per-frame bitmap accumulation / GC pressure in live analysis.
      // This bitmap is a temporary downscaled analysis image.
      try {
        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      image.close();
    }
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

  private android.graphics.PointF[] mapToOverlayPoints(
      org.opencv.core.Point[] src, int bmpW, int bmpH) {
    if (binding == null) return null;
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
    android.graphics.PointF[] out = new android.graphics.PointF[4];
    for (int i = 0; i < 4; i++) {
      float x = (float) src[i].x;
      float y = (float) src[i].y;
      out[i] = new android.graphics.PointF(offX + x * scale, offY + y * scale);
    }
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

      // Keep behavior consistent with camera scan: always reset OCR state for a newly imported
      // image.
      OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
      ocrVm.resetForNewImage();

      // Navigate to next step depending on preference
      if (isAdded()) {
        boolean skipOcr = false;
        boolean skipCropping = false;
        Context ctx = getContext();
        if (ctx != null) {
          android.content.SharedPreferences prefs =
              ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
          skipOcr = prefs.getBoolean("skip_ocr", false);
          skipCropping = prefs.getBoolean("skip_cropping", false);
        }
        int dest =
            skipCropping
                ? (skipOcr ? R.id.navigation_export : R.id.navigation_ocr)
                : R.id.navigation_crop;
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
    PdfImportHelper.handlePdfImport(this, pdfUri, this::processPdfBitmap);
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
    }

    // Keep behavior consistent with camera scan: reset OCR state for a newly imported PDF page.
    OCRViewModel ocrVm = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
    ocrVm.resetForNewImage();

    // Navigation based on settings
    boolean skipOcr = false;
    boolean skipCropping = false;
    Context ctx = getContext();
    if (ctx != null) {
      android.content.SharedPreferences prefs =
          ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
      skipOcr = prefs.getBoolean("skip_ocr", false);
      skipCropping = prefs.getBoolean("skip_cropping", false);
    }

    int dest =
        skipCropping
            ? (skipOcr ? R.id.navigation_export : R.id.navigation_ocr)
            : R.id.navigation_crop;

    try {
      Navigation.findNavController(requireView()).navigate(dest);
    } catch (IllegalArgumentException | IllegalStateException ignored) {
      // Best-effort; failure is non-critical
    }
  }
}
