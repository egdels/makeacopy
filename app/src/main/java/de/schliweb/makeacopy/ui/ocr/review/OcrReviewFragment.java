/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr.review;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import dagger.hilt.android.AndroidEntryPoint;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDoc;
import de.schliweb.makeacopy.ui.ocr.review.model.OcrDocReadingOrder;
import de.schliweb.makeacopy.ui.ocr.review.view.MinimapView;
import de.schliweb.makeacopy.ui.ocr.review.view.OcrOverlayView;
import de.schliweb.makeacopy.utils.ocr.DictionaryManager;
import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.OCRPostProcessor;
import de.schliweb.makeacopy.utils.ocr.OCRUtils;
import de.schliweb.makeacopy.utils.ocr.OcrModelManager;
import de.schliweb.makeacopy.utils.ocr.OcrPageSegmentationMode;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;
import de.schliweb.makeacopy.utils.ui.DialogUtils;
import de.schliweb.makeacopy.utils.ui.UIUtils;
import javax.inject.Inject;
import javax.inject.Provider;

/**
 * OcrReviewFragment is a subclass of Fragment that provides functionality for reviewing and editing
 * OCR (Optical Character Recognition) results. It handles user interactions, scale adjustments, and
 * autosave operations for OCR documents. The fragment supports features such as inline editing,
 * word operations (e.g., delete, merge, split), and language configuration.
 */
@AndroidEntryPoint
public class OcrReviewFragment extends Fragment {

  @Inject Provider<OCRHelper> ocrHelperProvider;
  @Inject DictionaryManager dictionaryManager;

  private boolean minimapVisible = true;

  private boolean initialFitApplied = false;

  private static final String TAG = "OcrReview";

  // Holds current minimap thumbnail to allow recycling on replacement
  @Nullable private Bitmap minimapBitmap;

  private void dbgWarn(String msg, Throwable t) {
    if (BuildConfig.DEBUG) Log.w(TAG, msg, t);
  }

  /**
   * Applies the given scale to various UI components, ensuring that the overlay, text layer, zoom
   * bar, and zoom chip remain synchronized.
   *
   * @param s The scale factor to be applied.
   * @param overlay The overlay view to apply the scale to.
   * @param textLayer The text layer view to apply the scale to.
   * @param zoomBar The zoom bar (seek bar) to update with the scaled value.
   * @param chipZoom The chip component to display the scale percentage and update its content
   *     description.
   */
  // Centralized scale/UI application to keep overlay, text layer, zoom bar and zoom chip in sync
  private void applyScale(
      float s,
      OcrOverlayView overlay,
      de.schliweb.makeacopy.ui.ocr.review.view.OcrTextLayerView textLayer,
      android.widget.SeekBar zoomBar,
      com.google.android.material.chip.Chip chipZoom) {
    if (overlay != null) overlay.setUserScale(s);
    if (textLayer != null) textLayer.setUserScale(s);
    if (zoomBar != null) {
      int prog = Math.min(200, Math.max(25, Math.round(s * 50f)));
      zoomBar.setProgress(prog);
    }
    if (chipZoom != null) {
      int pct = Math.round(s * 100f);
      chipZoom.setText(pct + "%");
      try {
        chipZoom.setContentDescription(getString(R.string.cd_zoom_chip_open_menu));
      } catch (Throwable t) {
        dbgWarn("Updating zoom chip contentDescription (applyScale) failed", t);
      }
    }
  }

  /**
   * Converts a given raw value into density-independent pixels (dp) based on the display metrics of
   * the device.
   *
   * @param v The raw value to be converted, typically provided in pixels.
   * @return The value converted to density-independent pixels (dp).
   */
  private float dp(float v) {
    return v * getResources().getDisplayMetrics().density;
  }

  /**
   * Loads and sets a small thumbnail bitmap into the minimap from the CropViewModel. Decodes with
   * downsampling to limit memory. Applies user rotation if set.
   */
  private void updateMinimapThumbnail(@NonNull MinimapView minimap) {
    try {
      if (getContext() == null) return;
      // Use in-memory bitmap from CropViewModel
      try {
        CropViewModel cvm =
            new androidx.lifecycle.ViewModelProvider(requireActivity()).get(CropViewModel.class);
        Bitmap bm = cvm != null ? cvm.getImageBitmap().getValue() : null;
        if (bm != null && !bm.isRecycled()) {
          // Apply user rotation if set
          Integer userRotDeg = cvm.getUserRotationDegrees().getValue();
          int rotDeg = (userRotDeg != null) ? userRotDeg : 0;
          Bitmap rotatedBm = (rotDeg != 0) ? rotateBitmap(bm, rotDeg) : bm;
          int w0 = rotatedBm.getWidth();
          int h0 = rotatedBm.getHeight();
          if (w0 > 0 && h0 > 0) {
            int maxDim0 = 512;
            int long0 = Math.max(w0, h0);
            float scale0 = long0 > maxDim0 ? (maxDim0 / (float) long0) : 1f;
            int tw = Math.max(1, Math.round(w0 * scale0));
            int th = Math.max(1, Math.round(h0 * scale0));
            Bitmap tiny = Bitmap.createScaledBitmap(rotatedBm, tw, th, true);
            // Recycle intermediate rotated bitmap if it was created
            if (rotDeg != 0 && rotatedBm != bm && !rotatedBm.isRecycled()) {
              try {
                rotatedBm.recycle();
              } catch (Throwable ignore3) {
                // Best-effort; failure is non-critical
              }
            }
            if (tiny != null) {
              Bitmap prev = minimapBitmap;
              minimapBitmap = tiny;
              minimap.setThumbnail(tiny);
              if (prev != null && prev != tiny && !prev.isRecycled()) {
                try {
                  prev.recycle();
                } catch (Throwable ignore2) {
                  // Best-effort; failure is non-critical
                }
              }
            }
          }
        }
      } catch (Throwable ignore2) {
        // Best-effort; failure is non-critical
      }
    } catch (Throwable t) {
      dbgWarn("Loading minimap thumbnail failed", t);
    }
  }

  /**
   * Computes the user scale factor required to achieve a "fit to screen" effect with padding,
   * clamped to a range of [0.5 .. 4.0].
   *
   * @param container The container view used for calculating the available dimensions. If null, a
   *     default scale of 1.0 is returned.
   * @param pageW The width of the content (e.g., page or image) to fit within the container, in
   *     pixels. Must be positive.
   * @param pageH The height of the content (e.g., page or image) to fit within the container, in
   *     pixels. Must be positive.
   * @param padPx The padding, in pixels, to be applied around the content when fitting to the
   *     container.
   * @return The computed scale factor in the range [0.5 .. 4.0]. Returns 1.0 if the input
   *     dimensions are invalid or the container is null.
   */
  // Computes the userScale that achieves a real "fit to screen" with padding, clamped to [0.5 ..
  // 4.0].
  private float computeFitScale(View container, int pageW, int pageH, float padPx) {
    if (container == null) return 1f;
    int cw = container.getWidth();
    int ch = container.getHeight();
    if (cw <= 0 || ch <= 0 || pageW <= 0 || pageH <= 0) return 1f;
    float vw =
        cw; // actual drawing area used by overlay/text layer (no extra padding considered here)
    float vh = ch;
    float sxPad = (vw - 2f * padPx) / pageW;
    float syPad = (vh - 2f * padPx) / pageH;
    float effAbs = Math.min(sxPad, syPad);
    // Base fit used by overlay/text layers (without padding)
    float sxBase = vw / (float) pageW;
    float syBase = vh / (float) pageH;
    float base = Math.min(sxBase, syBase);
    if (base <= 0f) return 1f;
    float user = effAbs / base; // convert absolute scale to userScale multiplier
    if (user < 0.5f) user = 0.5f;
    if (user > 4f) user = 4f;
    return user;
  }

  /**
   * Centers the viewport by resetting user offsets to (0,0), which corresponds to centered content
   * because userOffset is interpreted as a delta from base centering in our views.
   *
   * @param overlay The overlay view whose user offsets will be reset to center the viewport. If
   *     null, no operation is performed.
   */
  // Centers the viewport by resetting user offsets to (0,0), which corresponds to centered content
  // because userOffset is interpreted as a delta from base centering in our views.
  private void centerViewport(de.schliweb.makeacopy.ui.ocr.review.view.OcrOverlayView overlay) {
    if (overlay != null) overlay.setUserOffset(0f, 0f);
  }

  /**
   * Centers the viewport by resetting the user offsets of the provided text layer to (0, 0). This
   * corresponds to centering the content because the offsets are interpreted as a delta from the
   * base centering in the view.
   *
   * @param textLayer The text layer view whose user offsets will be reset to center the viewport.
   *     If null, no operation is performed.
   */
  private void centerViewport(de.schliweb.makeacopy.ui.ocr.review.view.OcrTextLayerView textLayer) {
    if (textLayer != null) textLayer.setUserOffset(0f, 0f);
  }

  private static final String STATE_MODE = "ocr_mode";
  private static final String STATE_SCALE = "ocr_scale";
  private static final String STATE_OFF_X = "ocr_off_x";
  private static final String STATE_OFF_Y = "ocr_off_y";
  private static final String STATE_MINIMAP_VISIBLE = "ocr_minimap_visible";
  private OcrReviewViewModel viewModel;
  // Editor write-back debounce handler (promoted to field for lifecycle cleanup)
  private final android.os.Handler editorHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());
  // Toolbar chips (action view) bound lazily via toolbar.post
  private android.widget.TextView chipWords;
  private android.widget.TextView chipLow;
  private android.widget.TextView chipLang;

  /**
   * Inflates the view for the fragment and initializes its components and observers. This method
   * sets up the user interface, binds views to logic, and prepares document data handling. It also
   * manages configuration settings for components like the toolbar, overlay, minimap, and segmented
   * controls.
   *
   * @param inflater The LayoutInflater object that can be used to inflate any views in the
   *     fragment.
   * @param container The parent view that this fragment's UI should be attached to (if not null).
   *     This value can be used to determine layout parameters for the inflated view.
   * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous
   *     saved state as given here.
   * @return The root View of the fragment's layout, or null if the inflater or container is null.
   */
  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    View root = inflater.inflate(R.layout.fragment_ocr_review, container, false);
    viewModel = new ViewModelProvider(requireActivity()).get(OcrReviewViewModel.class);

    // Top App Bar (chips live inside its action view)
    com.google.android.material.appbar.MaterialToolbar toolbar =
        root.findViewById(R.id.top_app_bar);
    if (toolbar != null) {
      // Use handleBackAction() to show discard dialog if there are unsaved changes
      toolbar.setNavigationOnClickListener(v -> handleBackAction());
    }

    // Handle system back (gesture/hardware button) the same way as X button
    androidx.activity.OnBackPressedCallback backCallback =
        new androidx.activity.OnBackPressedCallback(true) {
          @Override
          public void handleOnBackPressed() {
            handleBackAction();
          }
        };
    requireActivity()
        .getOnBackPressedDispatcher()
        .addCallback(getViewLifecycleOwner(), backCallback);
    OcrOverlayView overlay = root.findViewById(R.id.ocr_overlay);
    de.schliweb.makeacopy.ui.ocr.review.view.OcrTextLayerView textLayer =
        root.findViewById(R.id.ocr_text_layer);
    SeekBar zoomBar = null;
    View overlayContainer = root.findViewById(R.id.overlay_container);
    View textModeContainer = root.findViewById(R.id.text_mode_container);
    EditText textModeEditor = root.findViewById(R.id.text_mode_editor);
    com.google.android.material.button.MaterialButtonToggleGroup segmented =
        root.findViewById(R.id.segmented_group);
    com.google.android.material.button.MaterialButton segLayout =
        root.findViewById(R.id.seg_layout);
    com.google.android.material.chip.Chip chipZoom = root.findViewById(R.id.chip_zoom);

    // Document mode views
    View documentModeContainer = root.findViewById(R.id.document_mode_container);
    android.widget.ImageView documentImage = root.findViewById(R.id.document_image);
    OcrOverlayView documentOcrOverlay = root.findViewById(R.id.document_ocr_overlay);
    com.google.android.material.chip.Chip chipZoomDocument =
        root.findViewById(R.id.chip_zoom_document);
    // Minimap inside card placeholder
    final android.view.ViewGroup minimapCard = root.findViewById(R.id.minimap_placeholder);
    final MinimapView minimap = new MinimapView(requireContext());
    if (minimapCard != null) {
      // Ensure proper size inside card
      android.view.ViewGroup.LayoutParams lp =
          new android.view.ViewGroup.LayoutParams(
              android.view.ViewGroup.LayoutParams.MATCH_PARENT,
              android.view.ViewGroup.LayoutParams.MATCH_PARENT);
      minimap.setLayoutParams(lp);
      try {
        minimap.setContentDescription(getString(R.string.cd_minimap));
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      minimapCard.setClickable(true);
      if (minimapCard instanceof android.widget.FrameLayout) {
        minimapCard.addView(minimap);
      } else if (minimapCard instanceof com.google.android.material.card.MaterialCardView) {
        // MaterialCardView is a FrameLayout subclass; safe to cast
        minimapCard.addView(minimap);
      }
      // Apply current visibility state
      minimapCard.setVisibility(minimapVisible ? View.VISIBLE : View.GONE);
      // Try to load thumbnail preview for minimap
      updateMinimapThumbnail(minimap);
      // Navigation from minimap back to overlay/text layer
      minimap.setOnNavigateListener(
          new MinimapView.OnNavigateListener() {
            @Override
            public void onCenterRequested(float contentCenterX, float contentCenterY) {
              if (overlay == null) return;
              OcrDoc d = null;
              try {
                d = viewModel.getDoc().getValue();
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
              if (d == null || d.imageSize == null) return;
              int vw2 = overlay.getWidth();
              int vh2 = overlay.getHeight();
              if (vw2 <= 0 || vh2 <= 0) return;
              float baseFit =
                  Math.min(
                      vw2 / Math.max(1f, (float) d.imageSize.w),
                      vh2 / Math.max(1f, (float) d.imageSize.h));
              float eff = baseFit * overlay.getUserScale();
              float baseOffsetX2 = (vw2 - d.imageSize.w * eff) * 0.5f;
              float baseOffsetY2 = (vh2 - d.imageSize.h * eff) * 0.5f;
              float desiredUserOffsetX = (vw2 * 0.5f) - (baseOffsetX2 + contentCenterX * eff);
              float desiredUserOffsetY = (vh2 * 0.5f) - (baseOffsetY2 + contentCenterY * eff);
              overlay.setUserOffset(desiredUserOffsetX, desiredUserOffsetY);
              if (textLayer != null)
                textLayer.setUserOffset(desiredUserOffsetX, desiredUserOffsetY);
            }

            @Override
            public void onToggleZoomRequested() {
              float target;
              OcrDoc d = null;
              try {
                d = viewModel.getDoc().getValue();
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
              if (d != null && d.imageSize != null) {
                float padPx = dp(12f);
                float fit = computeFitScale(overlay, d.imageSize.w, d.imageSize.h, padPx);
                float cur = overlay != null ? overlay.getUserScale() : 1f;
                // Toggle: if currently around FIT, go to 100%; else go to FIT
                target = (Math.abs(cur - fit) < 0.05f) ? 1.0f : fit;
              } else target = 1.0f;
              applyScale(target, overlay, textLayer, zoomBar, chipZoom);
              if (Math.abs(target - 1.0f) > 0.01f) {
                centerViewport(overlay);
                centerViewport(textLayer);
              }
            }
          });
    }
    // Bind toolbar action view chips reliably using post()
    if (toolbar != null) {
      toolbar.post(
          () -> {
            try {
              android.view.Menu menu = toolbar.getMenu();
              android.view.MenuItem miStats =
                  (menu != null) ? menu.findItem(R.id.action_status_chips) : null;
              View statsView = miStats != null ? miStats.getActionView() : null;
              this.chipWords = statsView != null ? statsView.findViewById(R.id.chip_words) : null;
              this.chipLow = statsView != null ? statsView.findViewById(R.id.chip_low) : null;
              this.chipLang = statsView != null ? statsView.findViewById(R.id.chip_lang) : null;
              // Initial fill using current document, if available
              OcrDoc docNow = null;
              try {
                docNow = viewModel.getDoc().getValue();
              } catch (Throwable t) {
                dbgWarn("Getting doc for initial status chips failed", t);
              }
              updateStatusChips(docNow);
            } catch (Throwable t) {
              dbgWarn("Binding toolbar status chips failed", t);
            }
          });
    }

    final boolean[] updatingEditor = {false};
    // Guard flag to prevent infinite recursion between overlay and documentOcrOverlay viewport
    // listeners
    final boolean[] syncingViewport = {false};

    viewModel
        .getDoc()
        .observe(
            getViewLifecycleOwner(),
            doc -> {
              // Update minimap page size when document changes
              if (doc != null && doc.imageSize != null) {
                try {
                  if (minimapCard != null) {
                    // minimap variable exists in scope
                    minimap.setPageSize(doc.imageSize.w, doc.imageSize.h);
                    // Refresh thumbnail preview if available on disk
                    updateMinimapThumbnail(minimap);
                    // Provide simplified OCR boxes for optional minimap layer
                    if (doc.words != null) {
                      java.util.ArrayList<int[]> boxes =
                          new java.util.ArrayList<>(doc.words.size());
                      for (OcrDoc.Word w : doc.words) {
                        if (w == null || w.b == null || w.b.length < 4) continue;
                        boxes.add(new int[] {w.b[0], w.b[1], w.b[2], w.b[3]});
                      }
                      minimap.setOcrBoxes(boxes);
                    } else {
                      minimap.setOcrBoxes(null);
                    }
                    // Provide confidence heatmap (binned) based on word confidences
                    try {
                      int cols = 32, rows = 32;
                      int n = cols * rows;
                      int[] tot = new int[n];
                      int[] low = new int[n];
                      if (doc.words != null
                          && doc.imageSize != null
                          && doc.imageSize.w > 0
                          && doc.imageSize.h > 0) {
                        for (OcrDoc.Word w : doc.words) {
                          if (w == null || w.b == null || w.b.length < 4) continue;
                          float cx = w.b[0] + (w.b[2] * 0.5f);
                          float cy = w.b[1] + (w.b[3] * 0.5f);
                          int ix =
                              (int) Math.floor((cx / Math.max(1f, (float) doc.imageSize.w)) * cols);
                          int iy =
                              (int) Math.floor((cy / Math.max(1f, (float) doc.imageSize.h)) * rows);
                          if (ix < 0) ix = 0;
                          if (iy < 0) iy = 0;
                          if (ix >= cols) ix = cols - 1;
                          if (iy >= rows) iy = rows - 1;
                          int idx = iy * cols + ix;
                          tot[idx]++;
                          if (w.c <= 0.60f) low[idx]++;
                        }
                      }
                      float[] vals = new float[n];
                      for (int i = 0; i < n; i++) {
                        vals[i] = (tot[i] > 0) ? (low[i] / (float) tot[i]) : 0f;
                      }
                      minimap.setHeatmap(cols, rows, vals);
                    } catch (Throwable t) {
                      dbgWarn("Updating minimap heatmap failed", t);
                    }
                  }
                } catch (Throwable t) {
                  dbgWarn("Updating minimap page size/boxes failed", t);
                }
              }
              // Update status chips via single helper to keep logic DRY
              updateStatusChips(doc);
              if (overlay != null) overlay.setDoc(doc);
              if (textLayer != null) textLayer.setDoc(doc);
              if (textModeEditor != null) {
                try {
                  String text = buildFullText(doc);
                  // Avoid resetting cursor if text is identical
                  String current =
                      textModeEditor.getText() == null ? "" : textModeEditor.getText().toString();
                  if (!text.equals(current)) {
                    updatingEditor[0] = true;
                    textModeEditor.setText(text);
                    textModeEditor.setSelection(textModeEditor.getText().length());
                    updatingEditor[0] = false;
                  }
                } catch (Throwable t) {
                  dbgWarn("Failed updating TextMode editor from doc", t);
                  updatingEditor[0] = false;
                }
              }
            });

    // Bottom Button Container (BACK/SAVE) wiring
    android.widget.Button buttonBack = root.findViewById(R.id.button_back);
    android.widget.Button buttonSave = root.findViewById(R.id.button_save);
    View buttonContainer = root.findViewById(R.id.button_container);

    // Adjust button container margin for system navigation bar (like other fragments)
    if (buttonContainer != null) {
      UIUtils.adjustMarginForSystemInsets(buttonContainer, 12);
      androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
          buttonContainer,
          (v, insets) -> {
            UIUtils.adjustMarginForSystemInsets(buttonContainer, 12);
            return insets;
          });
    }

    if (buttonBack != null) {
      buttonBack.setOnClickListener(v -> handleBackAction());
    }

    if (buttonSave != null) {
      buttonSave.setOnClickListener(v -> handleSaveAction());
    }

    final boolean[] updatingZoomBar = {false};

    // Segmented control: toggle between Layout, Text, and Document modes
    if (segmented != null) {
      com.google.android.material.button.MaterialButtonToggleGroup.OnButtonCheckedListener toggle =
          (group, checkedId, isChecked) -> {
            if (!isChecked) return; // react only when a button becomes checked
            boolean showLayout = checkedId == R.id.seg_layout;
            boolean showText = checkedId == R.id.seg_text;
            boolean showDocument = checkedId == R.id.seg_document;

            // Update container visibility for all three modes
            if (overlayContainer != null)
              overlayContainer.setVisibility(showLayout ? View.VISIBLE : View.GONE);
            if (textModeContainer != null)
              textModeContainer.setVisibility(showText ? View.VISIBLE : View.GONE);
            if (documentModeContainer != null)
              documentModeContainer.setVisibility(showDocument ? View.VISIBLE : View.GONE);

            if (showLayout) {
              // Sync zoom and position from Document overlay to Layout overlay when switching back
              if (overlay != null && documentOcrOverlay != null) {
                overlay.setUserScale(documentOcrOverlay.getUserScale());
                overlay.setUserOffset(
                    documentOcrOverlay.getUserOffsetX(), documentOcrOverlay.getUserOffsetY());
                if (textLayer != null) {
                  textLayer.setUserScale(documentOcrOverlay.getUserScale());
                  textLayer.setUserOffset(
                      documentOcrOverlay.getUserOffsetX(), documentOcrOverlay.getUserOffsetY());
                }
                // Update zoom chip to match
                if (chipZoom != null) {
                  int pct = Math.round(overlay.getUserScale() * 100f);
                  chipZoom.setText(pct + "%");
                }
              }
            }

            if (showText && textModeEditor != null) {
              // Sync text editor with current document when switching to text mode
              try {
                OcrDoc doc = viewModel.getDoc().getValue();
                String text = buildFullText(doc);
                String current =
                    textModeEditor.getText() == null ? "" : textModeEditor.getText().toString();
                if (!text.equals(current)) {
                  updatingEditor[0] = true;
                  textModeEditor.setText(text);
                  textModeEditor.setSelection(textModeEditor.getText().length());
                  updatingEditor[0] = false;
                }
              } catch (Throwable t) {
                dbgWarn("Syncing text editor on mode switch failed", t);
              }
              textModeEditor.requestFocus();
            }

            if (showDocument) {
              // Load and display the original document image with OCR overlay
              try {
                CropViewModel cvm =
                    new ViewModelProvider(requireActivity()).get(CropViewModel.class);
                Bitmap bmp = cvm != null ? cvm.getImageBitmap().getValue() : null;
                if (bmp != null && !bmp.isRecycled() && documentImage != null) {
                  // Apply user rotation if set
                  Integer userRotDeg = cvm.getUserRotationDegrees().getValue();
                  int rotDeg = (userRotDeg != null) ? userRotDeg : 0;
                  Bitmap displayBmp = (rotDeg != 0) ? rotateBitmap(bmp, rotDeg) : bmp;
                  documentImage.setImageBitmap(displayBmp);
                }
                // Sync OCR overlay with document
                if (documentOcrOverlay != null) {
                  OcrDoc doc = viewModel.getDoc().getValue();
                  documentOcrOverlay.setDoc(doc);
                  // In Document mode, show only boxes without text content
                  documentOcrOverlay.setBoxesOnly(true);
                  documentOcrOverlay.setOnWordTapListener(
                      OcrReviewFragment.this::showInlineEditDialog);
                  documentOcrOverlay.setOnWordLongPressListener(
                      OcrReviewFragment.this::showContextMenu);
                  // Sync zoom and position from Layout overlay to Document overlay
                  if (overlay != null) {
                    documentOcrOverlay.setUserScale(overlay.getUserScale());
                    documentOcrOverlay.setUserOffset(
                        overlay.getUserOffsetX(), overlay.getUserOffsetY());
                  }
                  // Update document image matrix to match the overlay transformation
                  // Use post() to ensure the view has been laid out
                  documentImage.post(
                      () -> updateDocumentImageMatrix(documentImage, documentOcrOverlay));
                  // Update document zoom chip to match
                  if (chipZoomDocument != null) {
                    int pct = Math.round(documentOcrOverlay.getUserScale() * 100f);
                    chipZoomDocument.setText(pct + "%");
                  }
                }
              } catch (Throwable t) {
                dbgWarn("Loading document image failed", t);
              }
            }
          };
      segmented.addOnButtonCheckedListener(toggle);
      // initialize depending on current checked
      try {
        int checked = segmented.getCheckedButtonId();
        if (checked == View.NO_ID && segLayout != null && segLayout.isChecked())
          checked = R.id.seg_layout;
        toggle.onButtonChecked(segmented, checked, true);
      } catch (Throwable t) {
        dbgWarn("Initializing segmented control failed", t);
      }
    }

    // Text Mode editor write-back (macro-edit) with debounce to avoid undo bloat
    final Runnable applyEditorRunnable =
        () -> {
          try {
            if (textModeEditor == null) return;
            OcrDoc doc = viewModel.getDoc().getValue();
            if (doc == null || doc.words == null || doc.words.isEmpty()) return;
            String src =
                textModeEditor.getText() == null ? "" : textModeEditor.getText().toString();
            String trimmed = src.trim();
            String[] tokens = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
            int n = Math.min(doc.words.size(), tokens.length);
            if (n == 0) return;
            viewModel.markModified();
            for (int i = 0; i < n; i++) {
              OcrDoc.Word w = doc.words.get(i);
              if (w != null) {
                String nt = tokens[i];
                if (!nt.equals(w.t)) {
                  w.t = nt;
                  w.e = true;
                }
              }
            }
            viewModel.setDoc(doc);
            propagateAndAutosave();
          } catch (Throwable t) {
            dbgWarn("Applying editor changes failed", t);
          }
        };
    if (textModeEditor != null) {
      textModeEditor.addTextChangedListener(
          new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
              if (updatingEditor[0]) return;
              if (textModeContainer != null && textModeContainer.getVisibility() != View.VISIBLE)
                return;
              editorHandler.removeCallbacks(applyEditorRunnable);
              editorHandler.postDelayed(applyEditorRunnable, 250);
            }
          });
    }

    if (overlay != null) {
      overlay.setOnWordTapListener(this::showInlineEditDialog);
      overlay.setOnWordLongPressListener(this::showContextMenu);
      overlay.setOnViewportChangedListener(
          (scale, ox, oy) -> {
            // Guard against infinite recursion with documentOcrOverlay
            if (syncingViewport[0]) return;
            syncingViewport[0] = true;
            try {
              // Update minimap viewport too (throttled via postOnAnimation)
              if (minimapCard != null) {
                overlay.postOnAnimation(
                    () -> {
                      try {
                        minimap.setViewport(scale, ox, oy, overlay.getWidth(), overlay.getHeight());
                      } catch (Throwable t) {
                        dbgWarn("Updating minimap viewport failed", t);
                      }
                    });
              }
              // Sync text layer and zoom bar when user pans/zooms via gestures
              if (textLayer != null) {
                textLayer.setUserScale(scale);
                textLayer.setUserOffset(ox, oy);
              }
              // Sync document overlay when user pans/zooms in layout mode
              if (documentOcrOverlay != null) {
                documentOcrOverlay.setUserScale(scale);
                documentOcrOverlay.setUserOffset(ox, oy);
              }
              if (zoomBar != null) {
                updatingZoomBar[0] = true;
                int prog =
                    Math.round(scale * 50f); // inverse of mapping below (progress 50 -> 1.0x)
                if (prog < 25) prog = 25; // 0.5 min
                if (prog > 200) prog = 200; // 4.0 max
                zoomBar.setProgress(prog);
                updatingZoomBar[0] = false;
              }
              if (chipZoom != null) {
                try {
                  int pct = Math.round(scale * 100f);
                  chipZoom.setText(pct + "%");
                  try {
                    chipZoom.setContentDescription(getString(R.string.cd_zoom_chip_open_menu));
                  } catch (Throwable t2) {
                    dbgWarn("Updating zoom chip contentDescription failed", t2);
                  }
                } catch (Throwable t) {
                  dbgWarn("Updating zoom chip text failed", t);
                }
              }
              // Update document zoom chip too
              if (chipZoomDocument != null) {
                try {
                  int pct = Math.round(scale * 100f);
                  chipZoomDocument.setText(pct + "%");
                } catch (Throwable t) {
                  dbgWarn("Updating document zoom chip text failed", t);
                }
              }
            } finally {
              syncingViewport[0] = false;
            }
          });
    }

    // Document overlay viewport sync: when user pans/zooms in document mode, sync back to layout
    // overlay
    if (documentOcrOverlay != null) {
      documentOcrOverlay.setOnViewportChangedListener(
          (scale, ox, oy) -> {
            // Guard against infinite recursion with overlay
            if (syncingViewport[0]) return;
            syncingViewport[0] = true;
            try {
              // Sync layout overlay and text layer when user pans/zooms in document mode
              if (overlay != null) {
                overlay.setUserScale(scale);
                overlay.setUserOffset(ox, oy);
              }
              if (textLayer != null) {
                textLayer.setUserScale(scale);
                textLayer.setUserOffset(ox, oy);
              }
              // Update document image matrix to match the overlay transformation
              updateDocumentImageMatrix(documentImage, documentOcrOverlay);
              // Update layout zoom chip
              if (chipZoom != null) {
                try {
                  int pct = Math.round(scale * 100f);
                  chipZoom.setText(pct + "%");
                } catch (Throwable t) {
                  dbgWarn("Updating layout zoom chip from document failed", t);
                }
              }
              // Update document zoom chip
              if (chipZoomDocument != null) {
                try {
                  int pct = Math.round(scale * 100f);
                  chipZoomDocument.setText(pct + "%");
                } catch (Throwable t) {
                  dbgWarn("Updating document zoom chip failed", t);
                }
              }
            } finally {
              syncingViewport[0] = false;
            }
          });
    }

    // Zoom bar wiring: map 0..200 → userScale in [0.5 .. 4.0] (with min clamp at 0.5)
    if (zoomBar != null) {
      SeekBar.OnSeekBarChangeListener listener =
          new SeekBar.OnSeekBarChangeListener() {
            @SuppressWarnings("UnusedVariable") // fromUser required by SeekBar API
            private void apply(int progress, boolean fromUser) {
              if (updatingZoomBar[0]) return;
              float s = progress / 50f; // 50 -> 1.0, 200 -> 4.0, 0 -> 0.0 (clamped below)
              if (s < 0.5f) s = 0.5f;
              if (textLayer != null) textLayer.setUserScale(s);
              if (overlay != null) overlay.setUserScale(s);
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
              apply(progress, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
          };
      zoomBar.setOnSeekBarChangeListener(listener);
      // initialize
      listener.onProgressChanged(zoomBar, zoomBar.getProgress(), false);
      if (chipZoom != null) {
        try {
          chipZoom.setContentDescription(getString(R.string.cd_zoom_chip_open_menu));
        } catch (Throwable t) {
          dbgWarn("Updating zoom chip contentDescription (init) failed", t);
        }
      }
    }

    // Zoom chip: show popover with zoom levels and Fit actions
    if (chipZoom != null) {
      chipZoom.setOnClickListener(
          v -> {
            androidx.appcompat.widget.PopupMenu pm =
                new androidx.appcompat.widget.PopupMenu(requireContext(), v);
            android.view.Menu m = pm.getMenu();
            final int ID_100 = 1,
                ID_200 = 2,
                ID_300 = 3,
                ID_400 = 4,
                ID_FIT = 5,
                ID_TOGGLE_MINIMAP = 6;
            m.add(0, ID_100, 0, getString(R.string.zoom_100));
            m.add(0, ID_200, 1, getString(R.string.zoom_200));
            m.add(0, ID_300, 2, getString(R.string.zoom_300));
            m.add(0, ID_400, 3, getString(R.string.zoom_400));
            m.add(0, ID_FIT, 4, getString(R.string.zoom_fit));
            // Use simple English label if string resources are unavailable
            m.add(0, ID_TOGGLE_MINIMAP, 5, (minimapVisible ? "Hide minimap" : "Show minimap"));
            pm.setOnMenuItemClickListener(
                item -> {
                  int id = item.getItemId();
                  float target = 1.0f;
                  if (id == ID_100) target = 1.0f;
                  else if (id == ID_200) target = 2.0f;
                  else if (id == ID_300) target = 3.0f;
                  else if (id == ID_400) target = 4.0f;
                  else if (id == ID_FIT) {
                    // Compute real fit-to-screen user scale with small padding
                    OcrDoc d = null;
                    try {
                      d = viewModel.getDoc().getValue();
                    } catch (Throwable t) {
                      dbgWarn("Getting doc for FIT failed", t);
                    }
                    if (d != null && d.imageSize != null) {
                      float padPx = dp(12f);
                      float fit = computeFitScale(overlay, d.imageSize.w, d.imageSize.h, padPx);
                      target = fit;
                    } else {
                      target = 1.0f;
                    }
                  }

                  // Apply scale consistently across views and UI
                  applyScale(target, overlay, textLayer, zoomBar, chipZoom);

                  // Center viewport on FIT only
                  if (id == ID_FIT) {
                    centerViewport(overlay);
                    centerViewport(textLayer);
                  }
                  if (id == ID_TOGGLE_MINIMAP) {
                    try {
                      minimapVisible = !minimapVisible;
                      View card =
                          getView() != null
                              ? getView().findViewById(R.id.minimap_placeholder)
                              : null;
                      if (card != null)
                        card.setVisibility(minimapVisible ? View.VISIBLE : View.GONE);
                    } catch (Throwable ignore) {
                      // Best-effort; failure is non-critical
                    }
                    return true;
                  }
                  return true;
                });
            pm.show();
          });
    }

    // Document mode zoom chip: show popover with zoom levels and Fit actions
    if (chipZoomDocument != null) {
      chipZoomDocument.setOnClickListener(
          v -> {
            androidx.appcompat.widget.PopupMenu pm =
                new androidx.appcompat.widget.PopupMenu(requireContext(), v);
            android.view.Menu m = pm.getMenu();
            final int ID_100 = 1, ID_200 = 2, ID_300 = 3, ID_400 = 4, ID_FIT = 5;
            m.add(0, ID_100, 0, getString(R.string.zoom_100));
            m.add(0, ID_200, 1, getString(R.string.zoom_200));
            m.add(0, ID_300, 2, getString(R.string.zoom_300));
            m.add(0, ID_400, 3, getString(R.string.zoom_400));
            m.add(0, ID_FIT, 4, getString(R.string.zoom_fit));
            pm.setOnMenuItemClickListener(
                item -> {
                  int id = item.getItemId();
                  float target = 1.0f;
                  if (id == ID_100) target = 1.0f;
                  else if (id == ID_200) target = 2.0f;
                  else if (id == ID_300) target = 3.0f;
                  else if (id == ID_400) target = 4.0f;
                  else if (id == ID_FIT) {
                    // Compute real fit-to-screen user scale with small padding
                    OcrDoc d = null;
                    try {
                      d = viewModel.getDoc().getValue();
                    } catch (Throwable t) {
                      dbgWarn("Getting doc for FIT (document mode) failed", t);
                    }
                    if (d != null && d.imageSize != null) {
                      float padPx = dp(12f);
                      float fit =
                          computeFitScale(documentOcrOverlay, d.imageSize.w, d.imageSize.h, padPx);
                      target = fit;
                    } else {
                      target = 1.0f;
                    }
                  }

                  // Apply scale to document overlay
                  if (documentOcrOverlay != null) {
                    documentOcrOverlay.setUserScale(target);
                    // Center viewport on FIT only
                    if (id == ID_FIT) {
                      documentOcrOverlay.setUserOffset(0f, 0f);
                    }
                    // Update document image matrix to match
                    updateDocumentImageMatrix(documentImage, documentOcrOverlay);
                  }

                  // Update document zoom chip text
                  int pct = Math.round(target * 100f);
                  chipZoomDocument.setText(pct + "%");

                  // Sync to layout overlay and text layer for consistency when switching modes
                  if (overlay != null) {
                    overlay.setUserScale(target);
                    if (id == ID_FIT) {
                      overlay.setUserOffset(0f, 0f);
                    }
                  }
                  if (textLayer != null) {
                    textLayer.setUserScale(target);
                    if (id == ID_FIT) {
                      textLayer.setUserOffset(0f, 0f);
                    }
                  }
                  // Update layout zoom chip too
                  if (chipZoom != null) {
                    chipZoom.setText(pct + "%");
                  }

                  return true;
                });
            pm.show();
          });
    }

    // One-time initial Fit after layout so start state sits perfectly
    if (overlay != null) {
      overlay.post(
          () -> {
            if (initialFitApplied) return;
            OcrDoc d = null;
            try {
              d = viewModel.getDoc().getValue();
            } catch (Throwable t) {
              dbgWarn("Getting doc for initial FIT failed", t);
            }
            if (d == null || d.imageSize == null) return;
            float padPx = dp(12f);
            float target = computeFitScale(overlay, d.imageSize.w, d.imageSize.h, padPx);
            // Apply scale consistently
            applyScale(target, overlay, textLayer, zoomBar, chipZoom);
            // Center viewport on initial FIT
            centerViewport(overlay);
            centerViewport(textLayer);
            if (zoomBar != null) {
              updatingZoomBar[0] = true;
              zoomBar.setProgress(Math.round(target * 50f));
              updatingZoomBar[0] = false;
            }
            if (chipZoom != null) {
              try {
                int pct = Math.round(target * 100f);
                chipZoom.setText(pct + "%");
                try {
                  chipZoom.setContentDescription(getString(R.string.cd_zoom_chip_open_menu));
                } catch (Throwable t2) {
                  dbgWarn("Updating zoom chip contentDescription (initial fit) failed", t2);
                }
              } catch (Throwable t) {
                dbgWarn("Updating zoom chip after initial fit failed", t);
              }
            }
            try {
              if (minimapCard != null)
                minimap.setViewport(
                    overlay.getUserScale(),
                    overlay.getUserOffsetX(),
                    overlay.getUserOffsetY(),
                    overlay.getWidth(),
                    overlay.getHeight());
            } catch (Throwable t) {
              dbgWarn("Minimap initial viewport failed", t);
            }
            initialFitApplied = true;
          });
    }

    return root;
  }

  /**
   * Displays an inline edit dialog for modifying the properties of the given word. The dialog
   * includes a text input field for editing the word, dictionary-based suggestions, and save/cancel
   * buttons.
   *
   * @param word the word object to be edited. Contains the current text and edit state to be
   *     updated by this dialog.
   */
  private void showInlineEditDialog(OcrDoc.Word word) {
    if (getContext() == null) return;

    // Bottom Sheet im M3-Look (über dein Overlay)
    final BottomSheetDialog sheet =
        new BottomSheetDialog(requireContext(), R.style.ThemeOverlay_MakeACopy_BottomSheet);

    // Layout (vertikal)
    android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    int pad = (int) (16 * getResources().getDisplayMetrics().density);
    root.setPadding(pad, pad, pad, pad);

    // Title (unified options bottom sheet pattern, see DialogUtils.createOptionsBottomSheet)
    android.widget.TextView titleView = new android.widget.TextView(requireContext());
    titleView.setText(R.string.edit_word_title);
    titleView.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
    titleView.setPadding(0, 0, 0, (int) (8 * getResources().getDisplayMetrics().density));
    androidx.core.view.ViewCompat.setAccessibilityHeading(titleView, true);
    root.addView(titleView);

    // TextInput
    final com.google.android.material.textfield.TextInputLayout til =
        new com.google.android.material.textfield.TextInputLayout(requireContext());
    til.setHint(getString(R.string.edit_word_title));
    til.setBoxBackgroundMode(
        com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
    til.setCounterEnabled(true);
    til.setCounterMaxLength(64);

    final com.google.android.material.textfield.TextInputEditText input =
        new com.google.android.material.textfield.TextInputEditText(requireContext());
    input.setText(word.t == null ? "" : word.t);
    if (input.getText() != null) input.setSelection(input.getText().length());
    input.setSingleLine(false);
    input.setMinLines(1);
    input.setMaxLines(4);
    input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);

    til.addView(
        input,
        new android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    root.addView(
        til,
        new android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    if (!BuildConfig.FEATURE_PADDLE_OCR) {
      // Suggestions section (ChipGroup with dictionary suggestions)
      com.google.android.material.chip.ChipGroup suggestionsGroup =
          new com.google.android.material.chip.ChipGroup(requireContext());
      suggestionsGroup.setSingleLine(false);
      android.widget.LinearLayout.LayoutParams suggestionsLp =
          new android.widget.LinearLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      suggestionsLp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
      root.addView(suggestionsGroup, suggestionsLp);

      // Load suggestions asynchronously
      loadSuggestionsAsync(word.t, suggestionsGroup, input);
    }

    // Actions row
    android.widget.LinearLayout actions = new android.widget.LinearLayout(requireContext());
    actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    actions.setGravity(android.view.Gravity.END);

    android.widget.Space spacer = new android.widget.Space(requireContext());
    actions.addView(spacer, new android.widget.LinearLayout.LayoutParams(0, 0, 1f));

    // Unified button styles: Cancel outlined, Save filled (matching
    // DialogUtils.createOptionsBottomSheet)
    MaterialButton btnCancel =
        new MaterialButton(
            requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    btnCancel.setText(getString(R.string.cancel));
    btnCancel.setOnClickListener(v -> sheet.dismiss());

    MaterialButton btnSave = new MaterialButton(requireContext());
    btnSave.setText(R.string.btn_save);
    btnSave.setOnClickListener(
        v -> {
          String newText = input.getText() == null ? null : input.getText().toString().trim();
          if (newText == null || newText.isEmpty()) {
            til.setError(getString(R.string.error_empty_input));
            return;
          }
          til.setError(null);
          viewModel.markModified();
          word.t = newText;
          word.e = true;
          // Re-set the document to trigger LiveData observers and refresh the overlay
          OcrDoc doc = viewModel.getDocValue();
          if (doc != null) {
            viewModel.setDoc(doc);
          }
          propagateAndAutosave();
          sheet.dismiss();
        });

    // Add buttons with spacing between them
    actions.addView(btnCancel);
    android.widget.LinearLayout.LayoutParams saveLp =
        new android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    saveLp.setMarginStart((int) (8 * getResources().getDisplayMetrics().density));
    actions.addView(btnSave, saveLp);

    android.widget.LinearLayout.LayoutParams actionsLp =
        new android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    actionsLp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
    root.addView(actions, actionsLp);

    sheet.setContentView(root);
    sheet.show();

    input.setOnEditorActionListener(
        (v, actionId, event) -> {
          if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
            btnSave.performClick();
            return true;
          }
          return false;
        });
  }

  /**
   * Loads dictionary-based suggestions asynchronously and populates the ChipGroup.
   *
   * @param wordText the word text to get suggestions for
   * @param suggestionsGroup the ChipGroup to populate with suggestion chips
   * @param input the input field to update when a suggestion is clicked
   */
  private void loadSuggestionsAsync(
      String wordText,
      com.google.android.material.chip.ChipGroup suggestionsGroup,
      com.google.android.material.textfield.TextInputEditText input) {
    if (BuildConfig.FEATURE_PADDLE_OCR) {
      return;
    }
    if (getContext() == null || wordText == null || wordText.isEmpty()) {
      return;
    }

    // Get current OCR language from OCRViewModel
    String langSpec = null;
    try {
      de.schliweb.makeacopy.ui.ocr.OCRViewModel ocrViewModel =
          new ViewModelProvider(requireActivity())
              .get(de.schliweb.makeacopy.ui.ocr.OCRViewModel.class);
      de.schliweb.makeacopy.ui.ocr.OCRViewModel.OcrUiState state =
          ocrViewModel.getState().getValue();
      if (state != null) {
        langSpec = state.language();
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    final String finalLangSpec = langSpec;

    // Run suggestion lookup on background thread
    new Thread(
            () -> {
              try {
                de.schliweb.makeacopy.ui.ocr.review.suggest.DictionarySuggestProvider provider =
                    new de.schliweb.makeacopy.ui.ocr.review.suggest.DictionarySuggestProvider(
                        dictionaryManager, finalLangSpec);
                java.util.List<
                        de.schliweb.makeacopy.ui.ocr.review.suggest.DictionarySuggestProvider
                            .Suggestion>
                    suggestions = provider.getSuggestions(wordText);

                // Update UI on main thread
                if (getActivity() != null && !suggestions.isEmpty()) {
                  getActivity()
                      .runOnUiThread(
                          () -> {
                            if (getContext() == null) return;
                            for (de.schliweb.makeacopy.ui.ocr.review.suggest
                                    .DictionarySuggestProvider.Suggestion
                                suggestion : suggestions) {
                              com.google.android.material.chip.Chip chip =
                                  new com.google.android.material.chip.Chip(requireContext());
                              chip.setText(suggestion.text());
                              chip.setCheckable(false);
                              chip.setClickable(true);
                              chip.setOnClickListener(
                                  v -> {
                                    input.setText(suggestion.text());
                                    if (input.getText() != null) {
                                      input.setSelection(input.getText().length());
                                    }
                                  });
                              suggestionsGroup.addView(chip);
                            }
                          });
                }
              } catch (Throwable t) {
                dbgWarn("Failed to load suggestions", t);
              }
            })
        .start();
  }

  /**
   * Displays a context menu for the specified word, allowing the user to perform various actions,
   * such as editing, merging, splitting, deleting, changing case, or modifying the language.
   *
   * @param word the {@link OcrDoc.Word} instance for which the context menu is displayed
   */
  private void showContextMenu(OcrDoc.Word word) {
    if (getContext() == null) return;
    String[] items =
        new String[] {
          getString(R.string.cm_edit),
          getString(R.string.cm_merge_next),
          getString(R.string.cm_split),
          getString(R.string.cm_delete),
          getString(R.string.cm_case),
          getString(R.string.cm_lang)
        };
    AlertDialog dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setItems(
                items,
                (d, which) -> {
                  switch (which) {
                    case 0 -> showInlineEditDialog(word);
                    case 1 -> {
                      viewModel.markModified();
                      mergeWithNext(word);
                    }
                    case 2 -> {
                      viewModel.markModified();
                      splitWordMid(word);
                    }
                    case 3 -> {
                      viewModel.markModified();
                      deleteWord(word);
                    }
                    case 4 -> showCaseMenu(word);
                    case 5 -> showLanguageDialog(word);
                  }
                })
            .create();
    dialog.setOnShowListener(
        d -> DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));
    dialog.show();
  }

  /**
   * Displays a menu to change the case of the given word. The menu provides options to convert the
   * text to uppercase, lowercase, or title case. Changes are applied directly to the word and
   * propagated, with an autosave triggered.
   *
   * @param word the word object whose text is to be modified. It contains the current text and
   *     other properties.
   */
  private void showCaseMenu(OcrDoc.Word word) {
    String[] items =
        new String[] {
          getString(R.string.case_upper),
          getString(R.string.case_lower),
          getString(R.string.case_title)
        };
    AlertDialog dialog =
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cm_case)
            .setItems(
                items,
                (d, which) -> {
                  String t = word.t == null ? "" : word.t;
                  viewModel.markModified();
                  switch (which) {
                    case 0 -> word.t = t.toUpperCase(java.util.Locale.getDefault());
                    case 1 -> word.t = t.toLowerCase(java.util.Locale.getDefault());
                    case 2 -> word.t = toTitleCase(t);
                  }
                  word.e = true;
                  propagateAndAutosave();
                })
            .create();
    dialog.setOnShowListener(
        d -> DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));
    dialog.show();
  }

  /**
   * Displays a dialog to allow the user to select the language property of the given word from a
   * list of available OCR languages. Includes an option to re-run OCR for this word with the
   * selected language.
   *
   * @param word the {@link OcrDoc.Word} object whose language property will be edited
   */
  private void showLanguageDialog(OcrDoc.Word word) {
    if (getContext() == null) return;

    // Get available languages
    String[] codes = getAvailableLanguageCodes();
    String[] displayNames = mapCodesToDisplayNames(codes);

    // Find current selection index
    int currentIndex = -1;
    if (word.lang != null && !word.lang.isEmpty()) {
      for (int i = 0; i < codes.length; i++) {
        if (codes[i].equals(word.lang)) {
          currentIndex = i;
          break;
        }
      }
    }

    final int[] selectedIndex = {currentIndex};

    // Bottom Sheet im M3-Look (unified options bottom sheet pattern, see
    // DialogUtils.createOptionsBottomSheet)
    final BottomSheetDialog sheet =
        new BottomSheetDialog(requireContext(), R.style.ThemeOverlay_MakeACopy_BottomSheet);

    float density = getResources().getDisplayMetrics().density;
    android.widget.LinearLayout root = new android.widget.LinearLayout(requireContext());
    root.setOrientation(android.widget.LinearLayout.VERTICAL);
    int pad = (int) (16 * density);
    root.setPadding(pad, pad, pad, pad);

    // Title
    android.widget.TextView titleView = new android.widget.TextView(requireContext());
    titleView.setText(R.string.dialog_language_title);
    titleView.setTextAppearance(
        com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
    titleView.setPadding(0, 0, 0, (int) (8 * density));
    androidx.core.view.ViewCompat.setAccessibilityHeading(titleView, true);
    root.addView(titleView);

    // Single-choice language list (RadioGroup inside a ScrollView)
    android.widget.RadioGroup radioGroup = new android.widget.RadioGroup(requireContext());
    radioGroup.setOrientation(android.widget.LinearLayout.VERTICAL);
    for (int i = 0; i < displayNames.length; i++) {
      com.google.android.material.radiobutton.MaterialRadioButton rb =
          new com.google.android.material.radiobutton.MaterialRadioButton(requireContext());
      rb.setId(i);
      rb.setText(displayNames[i]);
      rb.setMinHeight((int) (48 * density));
      radioGroup.addView(rb);
    }
    if (currentIndex >= 0) radioGroup.check(currentIndex);
    radioGroup.setOnCheckedChangeListener((group, checkedId) -> selectedIndex[0] = checkedId);

    android.widget.ScrollView scroll = new android.widget.ScrollView(requireContext());
    scroll.addView(
        radioGroup,
        new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    android.widget.LinearLayout.LayoutParams scrollLp =
        new android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    root.addView(scroll, scrollLp);

    // Cap the list height so the action row stays visible with long language lists
    final int maxListHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
    scroll.post(
        () -> {
          if (scroll.getHeight() > maxListHeight) {
            scroll.getLayoutParams().height = maxListHeight;
            scroll.requestLayout();
          }
        });

    // Actions: Re-OCR on its own full-width row (three buttons side by side would not fit on
    // narrow screens), Cancel + Save right-aligned below
    android.widget.LinearLayout actions = new android.widget.LinearLayout(requireContext());
    actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
    actions.setGravity(android.view.Gravity.END);

    android.widget.Space spacer = new android.widget.Space(requireContext());
    actions.addView(spacer, new android.widget.LinearLayout.LayoutParams(0, 0, 1f));

    MaterialButton btnCancel =
        new MaterialButton(
            requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    btnCancel.setText(getString(R.string.cancel));
    btnCancel.setOnClickListener(v -> sheet.dismiss());

    MaterialButton btnReOcr =
        new MaterialButton(
            requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    btnReOcr.setText(R.string.btn_reocr);
    btnReOcr.setOnClickListener(
        v -> {
          if (selectedIndex[0] < 0 || selectedIndex[0] >= codes.length) {
            UIUtils.showToast(
                requireContext(),
                getString(R.string.reocr_no_language),
                android.widget.Toast.LENGTH_SHORT);
            return;
          }
          String newLang = codes[selectedIndex[0]];
          // Save the language first
          viewModel.markModified();
          word.lang = newLang;
          word.e = true;

          // Update status chips
          OcrDoc currentDoc = viewModel.getDoc().getValue();
          updateStatusChips(currentDoc);

          // Run Re-OCR for this word
          reOcrWord(word, newLang);
          sheet.dismiss();
        });

    MaterialButton btnSave = new MaterialButton(requireContext());
    btnSave.setText(R.string.btn_save);
    btnSave.setOnClickListener(
        v -> {
          viewModel.markModified();
          if (selectedIndex[0] >= 0 && selectedIndex[0] < codes.length) {
            word.lang = codes[selectedIndex[0]];
          } else {
            word.lang = null;
          }
          word.e = true;

          // Update status chips immediately to reflect the language change
          OcrDoc currentDoc = viewModel.getDoc().getValue();
          updateStatusChips(currentDoc);

          propagateAndAutosave();
          sheet.dismiss();
        });

    actions.addView(btnCancel);
    android.widget.LinearLayout.LayoutParams saveLp =
        new android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    saveLp.setMarginStart((int) (8 * density));
    actions.addView(btnSave, saveLp);

    android.widget.LinearLayout.LayoutParams reOcrLp =
        new android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    reOcrLp.topMargin = (int) (12 * density);
    root.addView(btnReOcr, reOcrLp);

    android.widget.LinearLayout.LayoutParams actionsLp =
        new android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    actionsLp.topMargin = (int) (8 * density);
    root.addView(actions, actionsLp);

    sheet.setContentView(root);
    sheet.show();
  }

  /**
   * Gets available OCR language codes.
   *
   * @return array of language codes (e.g., "eng", "deu", "fas")
   */
  private String[] getAvailableLanguageCodes() {
    try {
      String[] flavorLanguages = OcrModelManager.getAvailableLanguageCodes(requireContext());
      if (flavorLanguages != null && flavorLanguages.length > 0) return flavorLanguages;
      OCRHelper helper = ocrHelperProvider.get();
      String[] langs = helper.getAvailableLanguages();
      if (langs != null && langs.length > 0) return langs;
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    // Fallback to common languages
    return OCRUtils.getLanguages();
  }

  /**
   * Maps language codes to display names.
   *
   * @param codes array of language codes
   * @return array of display names
   */
  private String[] mapCodesToDisplayNames(String[] codes) {
    String[] out = new String[codes.length];
    for (int i = 0; i < codes.length; i++) {
      out[i] = codeToDisplayName(codes[i]);
    }
    return out;
  }

  /**
   * Converts a language code to a human-readable display name.
   *
   * @param code the language code (e.g., "eng", "deu")
   * @return the display name (e.g., "English", "German")
   */
  private String codeToDisplayName(String code) {
    if (de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR) {
      return switch (code) {
        case "en" -> "English (Paddle)";
        case "latin" -> "Latin script (Paddle)";
        case "eslav" -> "East Slavic (Paddle)";
        case "cyrillic" -> "Cyrillic script (Paddle)";
        case "arabic" -> "Arabic script (Paddle)";
        case "devanagari" -> "Devanagari script (Paddle)";
        case "th" -> "Thai (Paddle)";
        case "el" -> "Greek (Paddle)";
        case "zh" -> "Chinese/Japanese/Korean (Paddle)";
        default -> code + " (Paddle)";
      };
    }
    // Map common Tesseract 3-letter codes to 2-letter BCP-47 where possible
    String two;
    switch (code) {
      case "eng":
        two = "en";
        break;
      case "deu":
        two = "de";
        break;
      case "fra":
        two = "fr";
        break;
      case "ita":
        two = "it";
        break;
      case "spa":
        two = "es";
        break;
      case "por":
        two = "pt";
        break;
      case "nld":
        two = "nl";
        break;
      case "pol":
        two = "pl";
        break;
      case "ces":
        two = "cs";
        break;
      case "slk":
        two = "sk";
        break;
      case "hun":
        two = "hu";
        break;
      case "ron":
        two = "ro";
        break;
      case "dan":
        two = "da";
        break;
      case "nor":
        two = "no";
        break;
      case "swe":
        two = "sv";
        break;
      case "rus":
        two = "ru";
        break;
      case "tha":
        two = "th";
        break;
      case "fas":
        two = "fa";
        break;
      case "ara":
        two = "ar";
        break;
      case "hin":
        two = "hi";
        break;
      case "tur":
        two = "tr";
        break;
      case "chi_sim":
        return "Chinese (Simplified)";
      case "chi_tra":
        return "Chinese (Traditional)";
      default:
        // Fallback: try first two letters
        if (code != null && code.length() >= 2) {
          two = code.substring(0, 2);
        } else {
          two = "en";
        }
    }
    try {
      java.util.Locale loc = java.util.Locale.forLanguageTag(two);
      return loc.getDisplayLanguage(java.util.Locale.getDefault());
    } catch (Throwable ignore) {
      return code;
    }
  }

  /**
   * Re-runs OCR for a single word using the specified language. Extracts the word's bounding box
   * region from the original image and performs OCR.
   *
   * @param word the word to re-OCR
   * @param newLang the language code to use for OCR (e.g., "eng", "deu", "fas")
   */
  private void reOcrWord(OcrDoc.Word word, String newLang) {
    if (getContext() == null || word == null || word.b == null || word.b.length < 4) {
      UIUtils.showToast(
          requireContext(), getString(R.string.reocr_failed), android.widget.Toast.LENGTH_SHORT);
      return;
    }

    // Show processing indicator
    UIUtils.showToast(
        requireContext(), getString(R.string.reocr_processing), android.widget.Toast.LENGTH_SHORT);

    // Get the original image from CropViewModel
    android.graphics.Bitmap sourceBitmap = null;
    try {
      CropViewModel cvm = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
      sourceBitmap = cvm != null ? cvm.getImageBitmap().getValue() : null;
    } catch (Throwable t) {
      dbgWarn("Failed to get source bitmap for re-OCR", t);
    }

    if (sourceBitmap == null || sourceBitmap.isRecycled()) {
      UIUtils.showToast(
          requireContext(), getString(R.string.reocr_failed), android.widget.Toast.LENGTH_SHORT);
      return;
    }

    // Extract word region with some padding for better OCR results
    int x = Math.max(0, word.b[0] - 5);
    int y = Math.max(0, word.b[1] - 5);
    int w = Math.min(word.b[2] + 10, sourceBitmap.getWidth() - x);
    int h = Math.min(word.b[3] + 10, sourceBitmap.getHeight() - y);

    if (w <= 0 || h <= 0) {
      UIUtils.showToast(
          requireContext(), getString(R.string.reocr_failed), android.widget.Toast.LENGTH_SHORT);
      return;
    }

    final android.graphics.Bitmap wordBitmap;
    try {
      wordBitmap = android.graphics.Bitmap.createBitmap(sourceBitmap, x, y, w, h);
    } catch (Throwable t) {
      dbgWarn("Failed to extract word bitmap", t);
      UIUtils.showToast(
          requireContext(), getString(R.string.reocr_failed), android.widget.Toast.LENGTH_SHORT);
      return;
    }

    // Run OCR on background thread
    new Thread(
            () -> {
              String newText = null;
              Float newConfidence = null;
              try {
                OCRHelper ocrHelper = ocrHelperProvider.get();
                ocrHelper.setLanguage(newLang);
                // Use PSM_SINGLE_WORD for single word recognition
                ocrHelper.setPageSegmentationMode(OcrPageSegmentationMode.SINGLE_WORD);

                if (ocrHelper.initTesseract()) {
                  de.schliweb.makeacopy.utils.ocr.OCRHelper.OcrResultWords result =
                      ocrHelper.runOcrWithRetry(wordBitmap);
                  if (result != null && result.text != null) {
                    newText = result.text.trim();
                    // Extract confidence: prefer word-level confidence, fallback to mean confidence
                    if (result.words != null && !result.words.isEmpty()) {
                      // Use confidence from first recognized word (most relevant for single-word
                      // OCR)
                      float wordConf = result.words.get(0).getConfidence();
                      // Normalize: Tesseract returns 0-100, we need 0-1
                      newConfidence = (wordConf > 1.0f) ? (wordConf / 100f) : wordConf;
                    } else if (result.meanConfidence != null) {
                      // Fallback to mean confidence (0-100 -> 0-1)
                      newConfidence = result.meanConfidence / 100f;
                    }
                  }
                  ocrHelper.shutdown();
                }
              } catch (Throwable t) {
                Log.w(TAG, "Re-OCR failed", t);
              } finally {
                try {
                  if (wordBitmap != null && !wordBitmap.isRecycled()) {
                    wordBitmap.recycle();
                  }
                } catch (Throwable ignore) {
                  // Best-effort; failure is non-critical
                }
              }

              final String finalText = newText;
              final Float finalConfidence = newConfidence;
              if (getActivity() != null) {
                getActivity()
                    .runOnUiThread(
                        () -> {
                          if (finalText != null && !finalText.isEmpty()) {
                            // Update the word with new OCR result
                            viewModel.markModified();
                            word.t = finalText;
                            word.e = true;

                            // Update confidence if available (for color-coded display)
                            if (finalConfidence != null) {
                              word.c =
                                  Math.max(0f, Math.min(1f, finalConfidence)); // clamp to [0,1]
                            }

                            // Refresh the document view to update color-coded confidence display
                            OcrDoc doc = viewModel.getDocValue();
                            if (doc != null) {
                              viewModel.setDoc(doc);
                            }
                            propagateAndAutosave();

                            UIUtils.showToast(
                                requireContext(),
                                getString(R.string.reocr_success, finalText),
                                android.widget.Toast.LENGTH_SHORT);
                          } else {
                            UIUtils.showToast(
                                requireContext(),
                                getString(R.string.reocr_failed),
                                android.widget.Toast.LENGTH_SHORT);
                          }
                        });
              }
            })
        .start();
  }

  /**
   * Merges the given word with the succeeding word in the OCR document, updating text, bounding
   * box, and confidence score while maintaining document structure.
   *
   * <p>Adjusts the current word's text by concatenating it with the succeeding word's text. Updates
   * the bounding box and recalculates confidence using area-based weighting. Removes the succeeding
   * word from the document after merging.
   *
   * @param word The word object to be merged with its immediately succeeding word in the OCR
   *     document.
   */
  private void mergeWithNext(OcrDoc.Word word) {
    OcrDoc doc = viewModel.getDoc().getValue();
    if (doc == null || doc.words == null) return;
    int idx = indexOfWord(doc, word.id);
    if (idx < 0 || idx + 1 >= doc.words.size()) return;
    OcrDoc.Word next = doc.words.get(idx + 1);
    if (next == null) return;
    String t1 = word.t == null ? "" : word.t.trim();
    String t2 = next.t == null ? "" : next.t.trim();
    word.t = (t1.isEmpty() || t2.isEmpty()) ? (t1 + t2) : (t1 + " " + t2);
    // union bbox
    int x1 = Math.min(word.b[0], next.b[0]);
    int y1 = Math.min(word.b[1], next.b[1]);
    int r1 = Math.max(word.b[0] + word.b[2], next.b[0] + next.b[2]);
    int b1 = Math.max(word.b[1] + word.b[3], next.b[1] + next.b[3]);
    word.b[0] = x1;
    word.b[1] = y1;
    word.b[2] = Math.max(0, r1 - x1);
    word.b[3] = Math.max(0, b1 - y1);
    // area-weighted confidence based on bbox areas
    try {
      float a1 = Math.max(0f, word.b[2]) * Math.max(0f, word.b[3]);
      float a2 = Math.max(0f, next.b[2]) * Math.max(0f, next.b[3]);
      float denom = Math.max(1f, a1 + a2);
      word.c = ((word.c * a1) + (next.c * a2)) / denom;
    } catch (Throwable ignore) {
      // fallback to simple average if any issue occurs
      word.c = (word.c + next.c) * 0.5f;
    }
    word.e = true;
    // remove next
    doc.words.remove(idx + 1);
    viewModel.setDoc(doc);
    propagateAndAutosave();
  }

  /**
   * Checks if a text string contains predominantly RTL (Right-to-Left) characters. Used to
   * determine text direction for proper word splitting in RTL languages like Persian and Arabic.
   *
   * @param text the text to check
   * @return true if the text contains predominantly RTL characters, false otherwise
   */
  private boolean isRtlText(String text) {
    if (text == null || text.isEmpty()) return false;

    int rtlCount = 0;
    int ltrCount = 0;

    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      byte directionality = Character.getDirectionality(cp);

      if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT
          || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
          || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
          || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
        rtlCount++;
      } else if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT
          || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
          || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
        ltrCount++;
      }

      i += Character.charCount(cp);
    }

    return rtlCount > ltrCount;
  }

  /**
   * Splits a given word at its approximate midpoint or nearest whitespace, creating two separate
   * words while adjusting their bounding boxes proportionally based on content distribution. For
   * LTR text: first part goes to left box, second part to right box. For RTL text: first part goes
   * to right box, second part to left box (because RTL reads right-to-left).
   *
   * @param word the word to be split. Its bounding box, text, and related data will be updated to
   *     reflect the split, and a new word object will be created and added to the document
   *     immediately after the original word.
   */
  private void splitWordMid(OcrDoc.Word word) {
    OcrDoc doc = viewModel.getDoc().getValue();
    if (doc == null || doc.words == null) return;
    int idx = indexOfWord(doc, word.id);
    if (idx < 0) return;
    String t = word.t == null ? "" : word.t;
    if (t.length() < 2) return; // nothing to split
    int mid = t.length() / 2;
    // try to split at nearest space to mid
    int leftSpace = t.lastIndexOf(' ', mid);
    int rightSpace = t.indexOf(' ', mid);
    int splitAt = (leftSpace >= 1) ? leftSpace : (rightSpace > 0 ? rightSpace : mid);
    String a = t.substring(0, splitAt).trim();
    String b = t.substring(splitAt).trim();
    if (a.isEmpty() || b.isEmpty()) return;

    // bbox split by x mid, proportionally by code point counts for robustness with multi-codepoint
    // chars
    int x = word.b[0];
    int y = word.b[1];
    int w = word.b[2];
    int h = word.b[3];
    int lenA = a.codePointCount(0, a.length());
    int lenB = b.codePointCount(0, b.length());
    int totalLen = Math.max(1, lenA + lenB);
    int w1 = Math.max(1, Math.round(w * (lenA / (float) totalLen)));
    int w2 = Math.max(1, w - w1);

    boolean isRtl = isRtlText(t);

    // For RTL: first part (a) goes to RIGHT box, second part (b) goes to LEFT box
    // For LTR: first part (a) goes to LEFT box, second part (b) goes to RIGHT box
    // The word order in doc.words stays the same (a first, b second) for correct text concatenation
    if (isRtl) {
      // RTL: original word gets RIGHT box with first part, new word gets LEFT box with second part
      word.t = a;
      word.b[0] = x + w2; // move to right side
      word.b[2] = w1;
      word.e = true;
      OcrDoc.Word nw = new OcrDoc.Word();
      nw.id = nextId(doc);
      nw.t = b;
      nw.b = new int[] {x, y, w2, h}; // left side
      nw.c = word.c;
      nw.e = true;
      nw.l = word.l;
      nw.k = word.k;
      nw.lang = word.lang;
      doc.words.add(idx + 1, nw);
    } else {
      // LTR: original word gets LEFT box with first part, new word gets RIGHT box with second part
      word.t = a;
      word.b[2] = w1;
      word.e = true;
      OcrDoc.Word nw = new OcrDoc.Word();
      nw.id = nextId(doc);
      nw.t = b;
      nw.b = new int[] {x + w1, y, w2, h};
      nw.c = word.c;
      nw.e = true;
      nw.l = word.l;
      nw.k = word.k;
      nw.lang = word.lang;
      doc.words.add(idx + 1, nw);
    }
    viewModel.setDoc(doc);
    propagateAndAutosave();
  }

  /**
   * Deletes the specified word from the OCR document if it exists.
   *
   * @param word the word to be deleted from the OCR document
   */
  private void deleteWord(OcrDoc.Word word) {
    OcrDoc doc = viewModel.getDoc().getValue();
    if (doc == null || doc.words == null) return;
    int idx = indexOfWord(doc, word.id);
    if (idx < 0) return;
    doc.words.remove(idx);
    viewModel.setDoc(doc);
    propagateAndAutosave();
  }

  /**
   * Finds the index of a word in the given OcrDoc that matches the specified ID.
   *
   * @param doc the OcrDoc object containing a list of words
   * @param id the ID of the word to search for
   * @return the index of the word with the specified ID, or -1 if the word is not found
   */
  private int indexOfWord(OcrDoc doc, int id) {
    for (int i = 0; i < doc.words.size(); i++) {
      if (doc.words.get(i) != null && doc.words.get(i).id == id) return i;
    }
    return -1;
  }

  /**
   * Calculates the next available ID for a word in the given document by determining the maximum
   * existing ID and incrementing it by one.
   *
   * @param doc the OcrDoc instance containing a list of words for which the next ID is calculated
   * @return the next available ID as an integer
   */
  private int nextId(OcrDoc doc) {
    int max = 0;
    for (OcrDoc.Word w : doc.words) if (w != null) max = Math.max(max, w.id);
    return max + 1;
  }

  /**
   * Converts the given string to title case, where the first letter of each word is capitalized and
   * the remaining letters are in lowercase. Words are considered to be substrings separated by
   * whitespace.
   *
   * @param s the input string to be converted to title case, may be null or empty
   * @return the converted string in title case; returns an empty string if the input is null or
   *     empty
   */
  private String toTitleCase(String s) {
    if (s == null || s.isEmpty()) return "";
    String[] parts = s.toLowerCase(java.util.Locale.getDefault()).split("\\s+", -1);
    StringBuilder sb = new StringBuilder();
    for (String p : parts) {
      if (p.isEmpty()) continue;
      sb.append(Character.toUpperCase(p.charAt(0)))
          .append(p.length() > 1 ? p.substring(1) : "")
          .append(' ');
    }
    return sb.toString().trim();
  }

  /**
   * Notifies the ViewModel that the document has been modified. This triggers dirty tracking and UI
   * updates.
   */
  private void propagateAndAutosave() {
    OcrDoc current = viewModel.getDoc().getValue();
    if (current != null) {
      viewModel.markModified();
    }
  }

  /**
   * Updates the document image matrix to match the overlay's transformation. This ensures the
   * background image zooms and pans together with the OCR overlay.
   *
   * @param imageView the ImageView displaying the document image
   * @param overlayView the OcrOverlayView whose transformation should be applied
   */
  private void updateDocumentImageMatrix(
      @Nullable android.widget.ImageView imageView, @Nullable OcrOverlayView overlayView) {
    if (imageView == null || overlayView == null) return;
    android.graphics.drawable.Drawable drawable = imageView.getDrawable();
    if (drawable == null) return;

    int imgW = drawable.getIntrinsicWidth();
    int imgH = drawable.getIntrinsicHeight();
    int vw = imageView.getWidth();
    int vh = imageView.getHeight();

    if (imgW <= 0 || imgH <= 0 || vw <= 0 || vh <= 0) return;

    // Get the overlay's transformation parameters
    float userScale = overlayView.getUserScale();
    float userOffsetX = overlayView.getUserOffsetX();
    float userOffsetY = overlayView.getUserOffsetY();

    // Calculate the same transformation as OcrOverlayView.computeTransform()
    float sx = vw / (float) imgW;
    float sy = vh / (float) imgH;
    float base = Math.min(sx, sy);
    float eff = base * Math.max(0.5f, Math.min(4.0f, userScale)); // clamp to [0.5, 4.0]
    float contentW = imgW * eff;
    float contentH = imgH * eff;
    float baseOffsetX = (vw - contentW) * 0.5f;
    float baseOffsetY = (vh - contentH) * 0.5f;
    float offsetX = baseOffsetX + userOffsetX;
    float offsetY = baseOffsetY + userOffsetY;

    // Apply the transformation to the ImageView
    android.graphics.Matrix matrix = new android.graphics.Matrix();
    matrix.setScale(eff, eff);
    matrix.postTranslate(offsetX, offsetY);
    imageView.setImageMatrix(matrix);
  }

  /**
   * Updates the status chips to display information derived from the provided OcrDoc object. This
   * method handles the total count of words, the percentage of low-confidence words, and the most
   * frequent language detected in the OCR document. The chips are updated dynamically with this
   * information.
   *
   * @param doc the OcrDoc object containing OCR results, including words and their related
   *     information. Can be null. If null or if the document does not contain words, the chips will
   *     display default or placeholder values.
   */
  private void updateStatusChips(@Nullable OcrDoc doc) {
    try {
      int total = (doc != null && doc.words != null) ? doc.words.size() : 0;
      int low = 0;
      java.util.HashMap<String, Integer> langCount = new java.util.HashMap<>();
      if (doc != null && doc.words != null) {
        for (OcrDoc.Word w : doc.words) {
          if (w != null) {
            if (w.c <= 0.60f) low++;
            if (w.lang != null && !w.lang.trim().isEmpty()) {
              String key = w.lang.trim();
              langCount.put(key, 1 + (langCount.containsKey(key) ? langCount.get(key) : 0));
            }
          }
        }
      }
      if (chipWords != null)
        try {
          chipWords.setText(getString(R.string.words_count_suffix_format, total));
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      if (chipLow != null)
        try {
          int pct = (total > 0) ? Math.round((low * 100f) / total) : 0;
          String txt =
              (total > 0)
                  ? getString(R.string.low_percent_format, pct)
                  : getString(R.string.low_placeholder);
          chipLow.setText(txt);
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      if (chipLang != null) {
        String bestLang = null;
        int best = 0;
        for (java.util.Map.Entry<String, Integer> e : langCount.entrySet()) {
          if (e.getValue() > best) {
            best = e.getValue();
            bestLang = e.getKey();
          }
        }
        if (bestLang == null) bestLang = "—";
        try {
          chipLang.setText(bestLang.toUpperCase(java.util.Locale.ROOT));
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Constructs a full text representation from the provided OCR document by processing its lines
   * and words. If lines are available, it prioritizes their order; otherwise, it concatenates words
   * in their current order. Handles null or empty inputs gracefully.
   *
   * @param doc the OCR document containing lines and words. Can be null.
   * @return the constructed full text representation as a String. Returns an empty string if the
   *     input is null or if the content cannot be processed.
   */
  private String buildFullText(@Nullable OcrDoc doc) {
    if (doc == null) return "";
    java.util.List<RecognizedWord> words = toRecognizedWords(doc);
    String text =
        OCRPostProcessor.wordsToText(
            words, de.schliweb.makeacopy.utils.ocr.MultiColumnOcrPrefs.isEnabled(getContext()));
    if (text != null && !text.isEmpty()) return text;

    StringBuilder sb = new StringBuilder();
    try {
      boolean first = true;
      for (OcrDoc.Word w : OcrDocReadingOrder.sortedWordsForReading(doc)) {
        String t = w.t == null ? "" : w.t.trim();
        if (t.isEmpty()) continue;
        if (!first) sb.append(' ');
        sb.append(t);
        first = false;
      }
      return sb.toString();
    } catch (Throwable ignore) {
      return sb.toString();
    }
  }

  private java.util.List<RecognizedWord> toRecognizedWords(@Nullable OcrDoc doc) {
    java.util.List<RecognizedWord> reviewedWords = new java.util.ArrayList<>();
    if (doc == null || doc.words == null) return reviewedWords;
    for (OcrDoc.Word word : doc.words) {
      if (word != null && word.b != null && word.b.length >= 4) {
        android.graphics.RectF box =
            new android.graphics.RectF(
                word.b[0], word.b[1], word.b[0] + word.b[2], word.b[1] + word.b[3]);
        RecognizedWord rw =
            new RecognizedWord(word.t != null ? word.t : "", box, word.c, word.lang);
        rw.setBlockId(word.k);
        rw.setLineId(word.l);
        reviewedWords.add(rw);
      }
    }
    return reviewedWords;
  }

  /**
   * Handles the BACK button action. If there are unsaved changes, shows a confirmation dialog.
   * Otherwise, navigates back immediately.
   */
  private void handleBackAction() {
    if (viewModel.hasUnsavedChanges()) {
      // Show discard confirmation dialog
      AlertDialog dialog =
          new MaterialAlertDialogBuilder(requireContext())
              .setTitle(R.string.title_discard_changes)
              .setMessage(R.string.msg_discard_changes)
              .setPositiveButton(
                  R.string.btn_discard,
                  (dlg, which) -> {
                    viewModel.resetToOriginal();
                    navigateBack();
                  })
              .setNegativeButton(R.string.cancel, null)
              .create();
      dialog.setOnShowListener(
          d -> DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));
      dialog.show();
    } else {
      navigateBack();
    }
  }

  /** Navigates back using NavController to avoid infinite recursion with OnBackPressedCallback. */
  private void navigateBack() {
    try {
      androidx.navigation.NavController nav =
          androidx.navigation.Navigation.findNavController(requireView());
      if (!nav.popBackStack()) {
        // If popBackStack returns false, we're at the start destination
        requireActivity().finish();
      }
    } catch (Throwable t) {
      dbgWarn("navigateBack failed", t);
      try {
        requireActivity().finish();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
  }

  /**
   * Handles the SAVE button action. Validates and normalizes the document, then applies the review
   * result to the OCRViewModel and navigates back.
   */
  private void handleSaveAction() {
    OcrDoc doc = viewModel.getDocValue();
    if (doc == null) {
      navigateBack();
      return;
    }

    // Normalize: trim word texts and remove empty words
    if (doc.words != null) {
      java.util.Iterator<OcrDoc.Word> it = doc.words.iterator();
      while (it.hasNext()) {
        OcrDoc.Word w = it.next();
        if (w != null && w.t != null) {
          w.t = w.t.trim();
          if (w.t.isEmpty()) {
            it.remove();
          }
        }
      }
    }

    // Apply to OCRViewModel
    try {
      de.schliweb.makeacopy.ui.ocr.OCRViewModel ocrViewModel =
          new ViewModelProvider(requireActivity())
              .get(de.schliweb.makeacopy.ui.ocr.OCRViewModel.class);
      java.util.List<RecognizedWord> reviewedWords = toRecognizedWords(doc);
      String reviewedText =
          OCRPostProcessor.wordsToText(
              reviewedWords,
              de.schliweb.makeacopy.utils.ocr.MultiColumnOcrPrefs.isEnabled(getContext()));
      if (reviewedText == null || reviewedText.isEmpty()) reviewedText = buildFullText(doc);
      ocrViewModel.applyReviewResult(reviewedText, reviewedWords);
    } catch (Throwable t) {
      dbgWarn("Failed to apply review result to OCRViewModel", t);
    }

    // Mark as saved and navigate back
    viewModel.markSaved();
    navigateBack();
  }

  /**
   * Called when the fragment's view is destroyed.
   *
   * <p>This method is part of the fragment lifecycle and is invoked to allow cleanup of resources
   * tied to the fragment's UI, such as removing callbacks and references from handlers and other UI
   * components.
   *
   * <p>- Removes pending callbacks from the editorHandler to prevent memory leaks or undesired
   * behavior after the view is destroyed. - Releases references to toolbar chips to avoid leaking
   * UI components tied to the action view.
   *
   * <p>Always calls the superclass implementation to ensure proper cleanup in the fragment's
   * lifecycle.
   */
  @Override
  public void onDestroyView() {
    super.onDestroyView();
    editorHandler.removeCallbacksAndMessages(null);
    // Release toolbar chip references to avoid leaking the action view
    chipWords = null;
    chipLow = null;
    chipLang = null;
    // Recycle minimap bitmap to free memory
    try {
      if (minimapBitmap != null && !minimapBitmap.isRecycled()) {
        minimapBitmap.recycle();
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
    minimapBitmap = null;
  }

  /** This lifecycle method is called when the activity is paused. */
  @Override
  public void onPause() {
    super.onPause();
  }

  /**
   * Saves the current instance state of the activity to the provided {@link Bundle}. This method is
   * used to persist the state of UI elements and variables so they can be restored when the
   * activity is recreated.
   *
   * @param outState The {@link Bundle} in which to place the activity's state data. This Bundle is
   *     passed to the {@code onCreate} method if the process is killed and restarted.
   */
  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    View root = getView();
    if (root == null) return;
    com.google.android.material.button.MaterialButtonToggleGroup segmented =
        root.findViewById(R.id.segmented_group);
    de.schliweb.makeacopy.ui.ocr.review.view.OcrOverlayView overlay =
        root.findViewById(R.id.ocr_overlay);
    int mode = 0; // 0 = layout, 1 = text
    if (segmented != null) {
      int checked = segmented.getCheckedButtonId();
      mode = (checked == R.id.seg_text) ? 1 : 0;
    }
    outState.putInt(STATE_MODE, mode);
    if (overlay != null) {
      outState.putFloat(STATE_SCALE, overlay.getUserScale());
      outState.putFloat(STATE_OFF_X, overlay.getUserOffsetX());
      outState.putFloat(STATE_OFF_Y, overlay.getUserOffsetY());
    }
    outState.putBoolean(STATE_MINIMAP_VISIBLE, minimapVisible);
  }

  /**
   * This method is called immediately after the view has been created and binds any UI components
   * to their respective views, restoring their states if applicable. It ensures that UI elements
   * are in their appropriate states, like segmented buttons, overlays, and zoom functionality. It
   * also handles restoring scale, offsets, and visibility of certain components from the saved
   * instance state.
   *
   * @param view The root view associated with the fragment, used to find UI components within the
   *     layout.
   * @param savedInstanceState A Bundle object containing the saved state of the fragment, which is
   *     used to restore UI state, such as scale, offsets, visibility settings, and mode selections.
   */
  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    if (savedInstanceState == null) return;
    try {
      com.google.android.material.button.MaterialButtonToggleGroup segmented =
          view.findViewById(R.id.segmented_group);
      de.schliweb.makeacopy.ui.ocr.review.view.OcrOverlayView overlay =
          view.findViewById(R.id.ocr_overlay);
      de.schliweb.makeacopy.ui.ocr.review.view.OcrTextLayerView textLayer =
          view.findViewById(R.id.ocr_text_layer);
      android.widget.SeekBar zoomBar = null;
      com.google.android.material.chip.Chip chipZoom = view.findViewById(R.id.chip_zoom);

      int mode = savedInstanceState.getInt(STATE_MODE, 0);
      if (segmented != null) {
        segmented.check(mode == 1 ? R.id.seg_text : R.id.seg_layout);
      }

      float s = savedInstanceState.getFloat(STATE_SCALE, Float.NaN);
      float ox = savedInstanceState.getFloat(STATE_OFF_X, 0f);
      float oy = savedInstanceState.getFloat(STATE_OFF_Y, 0f);
      minimapVisible = savedInstanceState.getBoolean(STATE_MINIMAP_VISIBLE, true);
      // Apply minimap visibility to placeholder if present
      try {
        View card = view.findViewById(R.id.minimap_placeholder);
        if (card != null) card.setVisibility(minimapVisible ? View.VISIBLE : View.GONE);
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
      if (!Float.isNaN(s)) {
        // Prevent the one-time auto-fit from overriding restored state
        initialFitApplied = true;
        // Apply scale consistently
        applyScale(s, overlay, textLayer, zoomBar, chipZoom);
        // Restore pan offsets
        if (overlay != null) overlay.setUserOffset(ox, oy);
        if (textLayer != null) textLayer.setUserOffset(ox, oy);
      }
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Rotates the given Bitmap by the specified degree in a clockwise direction. If the degrees are a
   * multiple of 360, the original Bitmap is returned unchanged.
   *
   * @param src The Bitmap to be rotated. Must not be null.
   * @param degreesCW The number of degrees to rotate the Bitmap clockwise. Values outside the range
   *     [0, 360) will be normalized.
   * @return A new rotated Bitmap object, or the original Bitmap if no rotation is applied.
   */
  private static Bitmap rotateBitmap(Bitmap src, int degreesCW) {
    int deg = ((degreesCW % 360) + 360) % 360;
    if (deg == 0) return src;
    Matrix m = new Matrix();
    m.postRotate(deg);
    return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
  }
}
