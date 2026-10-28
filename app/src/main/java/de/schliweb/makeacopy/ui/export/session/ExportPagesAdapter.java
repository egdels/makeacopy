/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.export.session;

import android.graphics.Bitmap;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.utils.image.ImageDecodeUtils;
import de.schliweb.makeacopy.utils.image.RotationPolicy;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Adapter for a RecyclerView to display and manage a list of scanned pages for export purposes.
 *
 * <p>This adapter handles the display of pages, facilitates user interactions such as selecting,
 * removing, or reordering items, and communicates events back to the specified callbacks interface.
 * The pages are represented using the `CompletedScan` class and displayed in a custom layout
 * defined in the application.
 */
@SuppressWarnings("JavaUtilDate") // SimpleDateFormat requires Date
public class ExportPagesAdapter extends RecyclerView.Adapter<ExportPagesAdapter.PageVH> {
  // Simple in-memory LRU cache for small thumbnails
  private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<>(32);

  /**
   * A list that holds instances of {@link CompletedScan}, representing completed scans available
   * for processing, exporting, or display in the UI.
   *
   * <p>This list is used to manage the data set for the adapter, which binds the scan details to
   * the corresponding UI elements in a RecyclerView or similar component.
   *
   * <p>The list is immutable, as it is declared final and initialized with an {@link ArrayList}.
   * Modifications to the list content should be performed by replacing it with new instances using
   * methods like {@link #submitList(List)}.
   */
  private final List<CompletedScan> items = new ArrayList<>();

  /**
   * A reference to the callback interface that defines methods for handling user interactions and
   * actions within the adapter.
   *
   * <p>The callbacks are used for: - Detecting and handling remove button clicks for specific items
   * in the RecyclerView. - Handling page click events within the list. - Managing item reordering
   * operations, such as drag-and-drop functionality.
   *
   * <p>This field is passed during the construction of the adapter and represents the bridge
   * through which the adapter communicates user actions back to the hosting component.
   */
  private final Callbacks callbacks;

  /**
   * Constructs an instance of the ExportPagesAdapter, initializing it with the given callback
   * interface to handle user interactions and actions within a RecyclerView context.
   *
   * @param callbacks The callback interface used to handle events such as item removal, item
   *     clicks, and reordering. This parameter should not be null.
   */
  public ExportPagesAdapter(Callbacks callbacks) {
    this.callbacks = callbacks;
    setHasStableIds(false);
  }

  private static Bitmap decodeSampled(String path, int reqW, int reqH) {
    // Use centralized EXIF-neutral decoder for baked disk files
    try {
      return ImageDecodeUtils.decodeSampled(path, Math.max(1, reqW), Math.max(1, reqH));
    } catch (Throwable t) {
      return null;
    }
  }

  private static Bitmap ThumbCache_get(String key) {
    if (key == null) return null;
    Bitmap bmp = THUMB_CACHE.get(key);
    if (bmp != null && bmp.isRecycled()) {
      THUMB_CACHE.remove(key);
      return null;
    }
    return bmp;
  }

  private static void ThumbCache_put(String key, Bitmap bmp) {
    if (key != null && bmp != null && !bmp.isRecycled()) THUMB_CACHE.put(key, bmp);
  }

  private static boolean setImageBitmapIfDrawable(@NonNull ImageView view, Bitmap bmp) {
    if (bmp == null || bmp.isRecycled()) {
      view.setImageResource(R.drawable.ic_image);
      return false;
    }
    view.setImageBitmap(bmp);
    return true;
  }

  /**
   * Updates the current list of items with the provided list of completed scans. Clears the
   * existing items and adds all elements from the new list, then notifies the adapter that the
   * dataset has changed.
   *
   * @param list The new list of CompletedScan objects to replace the current items. If null, the
   *     list will be cleared.
   */
  public void submitList(List<CompletedScan> list) {
    items.clear();
    if (list != null) items.addAll(list);
    notifyDataSetChanged();
  }

  /**
   * Handles the movement of an item from one position to another within a dataset. This method
   * swaps the elements at the specified positions, updates the RecyclerView, and invokes a callback
   * if one is defined.
   *
   * @param fromPosition The initial position of the item to be moved. Must be within the bounds of
   *     the dataset.
   * @param toPosition The target position to which the item should be moved. Must be within the
   *     bounds of the dataset.
   * @return {@code true} if the item was successfully moved; {@code false} otherwise (e.g., if the
   *     positions are invalid or the same).
   */
  public boolean onItemMove(int fromPosition, int toPosition) {
    if (fromPosition < 0
        || toPosition < 0
        || fromPosition >= items.size()
        || toPosition >= items.size()) return false;
    if (fromPosition == toPosition) return false;
    Collections.swap(items, fromPosition, toPosition);
    notifyItemMoved(fromPosition, toPosition);
    if (callbacks != null) {
      callbacks.onReorder(fromPosition, toPosition);
    }
    return true;
  }

  /**
   * Creates and returns a new ViewHolder instance representing a single item in the RecyclerView.
   * The method inflates the layout corresponding to the specified item view type and initializes
   * the ViewHolder with it.
   *
   * @param parent The parent ViewGroup into which the new View will be added after it is bound to
   *     an adapter position. It is typically the RecyclerView itself.
   * @param viewType The view type of the new View. This allows for different layouts to be used for
   *     items in the RecyclerView if the adapter requires multiple view types.
   * @return A new instance of {@link PageVH}, which is initialized with the inflated item view.
   */
  @NonNull
  @Override
  public PageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v =
        LayoutInflater.from(parent.getContext()).inflate(R.layout.item_export_page, parent, false);
    return new PageVH(v);
  }

  /**
   * Binds data to the given {@link PageVH} ViewHolder at the specified position. This method
   * updates the title, thumbnail, and click listeners of the ViewHolder based on the data at the
   * given position from the {@code items} list.
   *
   * @param h The {@link PageVH} ViewHolder to which data is to be bound. Represents a single item
   *     view in the RecyclerView.
   * @param position The position of the item in the dataset. Used to retrieve data from the {@code
   *     items} list and to define the item's display context and functionality within the
   *     ViewHolder.
   */
  @Override
  public void onBindViewHolder(@NonNull PageVH h, int position) {
    CompletedScan s = items.get(position);
    // Title like "01 • HH:mm" (time only, no date)
    String title =
        String.format(
            Locale.getDefault(),
            "%02d • %s",
            position + 1,
            new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(s.createdAt())));
    h.title.setText(title);

    // Thumbnail strategy: prefer in-memory bitmap; else decode sampled from thumbPath or filePath.
    // Apply rotationDeg for in-memory; for disk sources only when orientationMode=="metadata".
    int deg = 0;
    try {
      deg = s.rotationDeg();
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    Bitmap bmp = s.inMemoryBitmap();
    if (bmp != null) {
      // Rotate in-memory bitmap according to rotationDeg (in-memory is not yet rotated)
      if ((deg % 360) != 0) {
        try {
          android.graphics.Matrix m = new android.graphics.Matrix();
          m.postRotate(deg);
          Bitmap rotated =
              android.graphics.Bitmap.createBitmap(
                  bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
          if (rotated != null) bmp = rotated;
        } catch (Throwable ignore) {
          // Best-effort; failure is non-critical
        }
      }
      setImageBitmapIfDrawable(h.thumb, bmp);
    } else {
      String basePath = (s.thumbPath() != null) ? s.thumbPath() : s.filePath();
      String cacheKey =
          basePath; // keep simple key; we rotate only for rare metadata entries inline
      Bitmap cached = ThumbCache_get(cacheKey);
      if (cached != null) {
        setImageBitmapIfDrawable(h.thumb, cached);
      } else {
        h.thumb.setImageResource(R.drawable.ic_image);
        String path = basePath;
        if (path != null) {
          // decode sampled to roughly view size
          Bitmap decoded = decodeSampled(path, 96, 128);
          if (decoded != null) {
            // Decide rotation via centralized policy to avoid drift
            boolean rotate = false;
            try {
              rotate = RotationPolicy.shouldRotateForThumbnail(true, s.orientationMode(), deg);
            } catch (Throwable ignore) {
              // Best-effort; failure is non-critical
            }
            Bitmap out = decoded;
            if (rotate) {
              try {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(((deg % 360) + 360) % 360);
                Bitmap rotated =
                    android.graphics.Bitmap.createBitmap(
                        decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
                if (rotated != null) out = rotated;
              } catch (Throwable ignore) {
                // Best-effort; failure is non-critical
              }
            }
            ThumbCache_put(cacheKey, out);
            setImageBitmapIfDrawable(h.thumb, out);
          }
        }
      }
    }

    // OCR badge: show [OCR] if ocrTextPath present & file exists; otherwise [⚠]
    String badge = null;
    int badgeBg = 0x33000000; // default semi-transparent
    String ocrPath = s.ocrTextPath();
    boolean hasOcr = false;
    if (ocrPath != null) {
      try {
        java.io.File f = new java.io.File(ocrPath);
        hasOcr = f.exists() && f.isFile();
      } catch (Throwable ignore) {
        // Best-effort; failure is non-critical
      }
    }
    if (hasOcr) {
      badge = "[OCR]";
      badgeBg = 0x8032CD32; // semi green
    } else {
      badge = "[⚠]";
      badgeBg = 0x80FFA500; // semi orange
    }
    h.badge.setText(badge);
    h.badge.setBackgroundColor(badgeBg);
    // OCR options of this page: OCR when missing, language, and the Review of existing OCR text
    if (callbacks != null) {
      h.badge.setOnClickListener(v -> callbacks.onOcrRequested(h.getBindingAdapterPosition()));
    } else {
      h.badge.setOnClickListener(null);
    }

    h.buttonRemove.setOnClickListener(
        v -> {
          if (callbacks != null) callbacks.onRemoveClicked(h.getBindingAdapterPosition());
        });
    // Click on the item (or thumbnail) selects the page
    View.OnClickListener select =
        v -> {
          if (callbacks != null) callbacks.onPageClicked(h.getBindingAdapterPosition());
        };
    h.itemView.setOnClickListener(select);
    h.thumb.setOnClickListener(select);

    // Accessibility: item description like "Page X of Y"
    try {
      android.content.Context ctx = h.itemView.getContext();
      int total = getItemCount();
      String cd = ctx.getString(de.schliweb.makeacopy.R.string.page_n_of_m, position + 1, total);
      h.itemView.setContentDescription(cd);
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }

    // Accessibility: expose contextual custom actions for Delete / Move left / Move right
    try {
      final int pos = position;
      ViewCompat.setAccessibilityDelegate(
          h.itemView,
          new androidx.core.view.AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                @NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
              super.onInitializeAccessibilityNodeInfo(host, info);
              android.content.Context ctx = host.getContext();
              // Always allow Delete
              CharSequence deleteLabel =
                  ctx.getString(de.schliweb.makeacopy.R.string.remove_this_page);
              AccessibilityNodeInfoCompat.AccessibilityActionCompat deleteAction =
                  new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                      de.schliweb.makeacopy.R.id.a11y_action_delete_page, deleteLabel);
              info.addAction(deleteAction);

              // Move left if not first
              if (pos > 0) {
                CharSequence leftLabel =
                    ctx.getString(de.schliweb.makeacopy.R.string.move_page_left);
                AccessibilityNodeInfoCompat.AccessibilityActionCompat moveLeft =
                    new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        de.schliweb.makeacopy.R.id.a11y_action_move_left, leftLabel);
                info.addAction(moveLeft);
              }
              // Move right if not last
              if (pos < getItemCount() - 1) {
                CharSequence rightLabel =
                    ctx.getString(de.schliweb.makeacopy.R.string.move_page_right);
                AccessibilityNodeInfoCompat.AccessibilityActionCompat moveRight =
                    new AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        de.schliweb.makeacopy.R.id.a11y_action_move_right, rightLabel);
                info.addAction(moveRight);
              }
            }

            @Override
            public boolean performAccessibilityAction(
                @NonNull View host, int action, android.os.Bundle args) {
              if (callbacks != null) {
                int adapterPos = h.getBindingAdapterPosition();
                if (adapterPos == RecyclerView.NO_POSITION)
                  return super.performAccessibilityAction(host, action, args);
                if (action == de.schliweb.makeacopy.R.id.a11y_action_delete_page) {
                  // Accessibility actions are explicit user intent; bypass the confirmation
                  // dialog used for the on-screen delete button.
                  callbacks.onRemoveConfirmed(adapterPos);
                  return true;
                } else if (action == de.schliweb.makeacopy.R.id.a11y_action_move_left) {
                  if (adapterPos > 0) {
                    boolean moved = onItemMove(adapterPos, adapterPos - 1);
                    if (moved && callbacks != null) callbacks.onReorder(adapterPos, adapterPos - 1);
                    return moved;
                  }
                } else if (action == de.schliweb.makeacopy.R.id.a11y_action_move_right) {
                  if (adapterPos < getItemCount() - 1) {
                    boolean moved = onItemMove(adapterPos, adapterPos + 1);
                    if (moved && callbacks != null) callbacks.onReorder(adapterPos, adapterPos + 1);
                    return moved;
                  }
                }
              }
              return super.performAccessibilityAction(host, action, args);
            }
          });
    } catch (Throwable ignore) {
      // Best-effort; failure is non-critical
    }
  }

  /**
   * Retrieves the total number of items currently managed by the adapter.
   *
   * @return The total count of items in the dataset managed by this adapter.
   */
  @Override
  public int getItemCount() {
    return items.size();
  }

  /**
   * Defines callback methods for handling user interactions and actions within a RecyclerView
   * adapter.
   *
   * <p>This interface is intended to be implemented by components that need to handle specific
   * events such as item removal, item clicks, and reordering operations in a list of items.
   */
  public interface Callbacks {
    /**
     * Handles the event when the remove button is clicked for a specific item in the list.
     * Typically used to remove the item at the given position from the data set or perform
     * associated actions.
     *
     * @param position The position of the item in the list that was clicked.
     */
    void onRemoveClicked(int position);

    /**
     * Handles a confirmed removal request for a page (e.g. issued via an accessibility custom
     * action), bypassing any confirmation UI. Implementations should remove the item directly.
     *
     * <p>The default implementation simply delegates to {@link #onRemoveClicked(int)} to preserve
     * existing behavior for callers that do not override this method.
     *
     * @param position The position of the item in the list to remove.
     */
    default void onRemoveConfirmed(int position) {
      onRemoveClicked(position);
    }

    /**
     * Handles the event when a page in the list is clicked. Typically used to select the page at
     * the given position.
     *
     * @param position The position of the clicked page in the list.
     */
    void onPageClicked(int position);

    /**
     * Handles the reordering of items in a list, typically in response to user actions such as
     * drag-and-drop operations. This method is intended to update the logical order of the items
     * and perform any related processing as necessary.
     *
     * @param fromPosition The initial position of the item before reordering.
     * @param toPosition The new position of the item after reordering.
     */
    void onReorder(int fromPosition, int toPosition);

    /**
     * Requests the OCR options of a specific page (run OCR, language, review the text).
     *
     * @param position Index of the page in the adapter.
     */
    void onOcrRequested(int position);
  }

  /**
   * Represents a ViewHolder for managing and displaying an individual page item in a RecyclerView
   * within the ExportPagesAdapter class. This ViewHolder contains references to the UI components
   * of a single item, facilitating the efficient binding of data and handling of user interactions.
   *
   * <p>Fields: - thumb: An ImageView used for displaying a thumbnail image associated with the
   * page. - title: A TextView used for displaying the title or label of the page. - buttonRemove:
   * An ImageButton used for handling user actions, such as removing the page.
   *
   * <p>Constructor: Initializes the PageVH instance, binding the UI components to their
   * corresponding views within the item layout by using their resource IDs.
   *
   * @see ExportPagesAdapter
   */
  static class PageVH extends RecyclerView.ViewHolder {
    ImageView thumb;
    TextView title;
    ImageButton buttonRemove;
    TextView badge;

    PageVH(@NonNull View itemView) {
      super(itemView);
      thumb = itemView.findViewById(R.id.page_thumb);
      title = itemView.findViewById(R.id.page_title);
      buttonRemove = itemView.findViewById(R.id.button_remove);
      badge = itemView.findViewById(R.id.page_badge);
    }
  }
}
