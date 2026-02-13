package de.schliweb.makeacopy.ui.library;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.library.ScanEntity;
import de.schliweb.makeacopy.utils.FileUtils;
import de.schliweb.makeacopy.utils.ImageDecodeUtils;

import java.io.File;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A RecyclerView.Adapter implementation that displays a list of scanned entities.
 * The adapter binds data from a {@link ScanEntity} list to the provided views
 * and handles click events via the {@link OnItemClickListener} interface.
 */
public class ScansAdapter extends RecyclerView.Adapter<ScansAdapter.VH> {
    private final List<ScanEntity> items = new ArrayList<>();
    private final OnItemClickListener listener;
    private OnItemLongClickListener longClickListener;

    // Map of scanId -> collection names for membership display
    private final Map<String, List<String>> memberships = new LinkedHashMap<>();

    // Simple in-memory LRU cache for small thumbnails
    private final Map<String, Bitmap> thumbCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            if (size() > 48) {
                Bitmap b = eldest.getValue();
                if (b != null && !b.isRecycled()) b.recycle();
                return true;
            }
            return false;
        }
    };
    private final ExecutorService loader = Executors.newFixedThreadPool(2);

    // Track missing/unreadable primary export per scan id to guard clicks and annotate UI
    private final Map<String, Boolean> unreadableMap = new LinkedHashMap<>();

    public interface OnItemClickListener {
        void onItemClick(@NonNull ScanEntity item);
    }

    public interface OnItemLongClickListener {
        void onItemLongClick(@NonNull ScanEntity item);
    }


    public ScansAdapter(@NonNull OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setOnItemLongClickListener(OnItemLongClickListener longClickListener) {
        this.longClickListener = longClickListener;
    }

    public void submitList(List<ScanEntity> data) {
        items.clear();
        if (data != null) items.addAll(data);
        notifyDataSetChanged();
    }

    public void setMemberships(Map<String, List<String>> map) {
        memberships.clear();
        if (map != null) memberships.putAll(map);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_scan_item, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ScanEntity e = items.get(position);
        String title = (e.title != null && !e.title.isEmpty()) ? e.title : e.id;
        h.title.setText(title);
        String dateStr = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(e.createdAt));
        String baseSubtitle = dateStr + " • " + Math.max(1, e.pageCount) + " page(s)";
        // Show base metadata in subtitle (date/pages)
        h.subtitle.setText(baseSubtitle);
        // Show collection membership on its own line (separate TextView)
        List<String> cols = memberships.get(e.id);
        if (h.membership != null) {
            if (cols != null && !cols.isEmpty()) {
                String mem = formatMembership(h.itemView, cols);
                if (mem != null && !mem.isEmpty()) {
                    h.membership.setText(mem);
                    h.membership.setVisibility(View.VISIBLE);
                } else {
                    h.membership.setText("");
                    h.membership.setVisibility(View.GONE);
                }
            } else {
                h.membership.setText("");
                h.membership.setVisibility(View.GONE);
            }
        }

        // Default click: navigate to details; if unreadable, show a hint toast first
        h.itemView.setOnClickListener(v -> {
            Boolean unread = unreadableMap.get(e.id);
            if (Boolean.TRUE.equals(unread)) {
                try {
                    de.schliweb.makeacopy.utils.UIUtils.showToast(v.getContext(), R.string.missing_file, android.widget.Toast.LENGTH_SHORT);
                } catch (Throwable ignore) {
                }
            }
            listener.onItemClick(e);
        });
        // Optional long-click callback (e.g., remove from collection)
        h.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onItemLongClick(e);
                return true;
            }
            return false;
        });

        // Set placeholder first
        h.thumb.setImageResource(android.R.drawable.ic_menu_report_image);
        h.thumb.setAlpha(1f);
        // Prefer explicit coverPath; fall back to first export URI when coverPath is missing
        String key = (e.coverPath != null && !e.coverPath.isEmpty()) ? e.coverPath : FileUtils.firstUriFromJson(e.exportPathsJson);
        if (key != null && !key.isEmpty()) {
            Bitmap cached = thumbCache.get(key);
            if (cached != null && !cached.isRecycled()) {
                h.thumb.setImageBitmap(cached);
            } else {
                // async load
                loader.submit(() -> {
                    Bitmap bmp = loadThumb(h.thumb, key);
                    if (bmp != null) {
                        synchronized (thumbCache) {
                            thumbCache.put(key, bmp);
                        }
                        // Bind back on UI thread if holder is still valid
                        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                        main.post(() -> {
                            int pos = h.getBindingAdapterPosition();
                            if (pos != RecyclerView.NO_POSITION) {
                                ScanEntity cur = items.get(pos);
                                String curKey = (cur.coverPath != null && !cur.coverPath.isEmpty()) ? cur.coverPath : FileUtils.firstUriFromJson(cur.exportPathsJson);
                                if (curKey != null && curKey.equals(key)) {
                                    h.thumb.setImageBitmap(bmp);
                                }
                            }
                        });
                    }
                });
            }
        }

        // Async readability check of the primary export URI to annotate subtitle and guard clicks
        final String primary = FileUtils.firstUriFromJson(e.exportPathsJson);
        if (primary != null && !primary.isEmpty()) {
            loader.submit(() -> {
                boolean readable = FileUtils.isUriReadable(h.itemView.getContext(), primary);
                unreadableMap.put(e.id, !readable);
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    int pos = h.getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION) {
                        ScanEntity cur = items.get(pos);
                        if (cur != null && cur.id.equals(e.id)) {
                            String dateStrCur = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(cur.createdAt));
                            String base = dateStrCur + " • " + Math.max(1, cur.pageCount) + " page(s)";
                            List<String> colsCur = memberships.get(cur.id);
                            String sub = base;
                            if (!readable) {
                                sub = sub + " • " + h.itemView.getContext().getString(R.string.missing_file);
                                try {
                                    h.thumb.setAlpha(0.7f);
                                } catch (Throwable ignore) {
                                }
                            }
                            h.subtitle.setText(sub);
                            if (h.membership != null) {
                                if (colsCur != null && !colsCur.isEmpty()) {
                                    String mem = formatMembership(h.itemView, colsCur);
                                    if (mem != null && !mem.isEmpty()) {
                                        h.membership.setText(mem);
                                        h.membership.setVisibility(View.VISIBLE);
                                    } else {
                                        h.membership.setText("");
                                        h.membership.setVisibility(View.GONE);
                                    }
                                } else {
                                    h.membership.setText("");
                                    h.membership.setVisibility(View.GONE);
                                }
                            }
                        }
                    }
                });
            });
        } else {
            unreadableMap.put(e.id, false);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // Formats the membership suffix like: "In: A, B" or "In: A, B +2"
    private String formatMembership(@NonNull View anyView, @NonNull List<String> names) {
        try {
            android.content.Context ctx = anyView.getContext();
            if (names == null || names.isEmpty()) return null;
            String text;
            if (names.size() <= 2) {
                String joined = android.text.TextUtils.join(", ", names);
                text = ctx.getString(R.string.in_collections_prefix, joined);
            } else {
                String joined = names.get(0) + ", " + names.get(1) + " +" + (names.size() - 2);
                text = ctx.getString(R.string.in_collections_prefix, joined);
            }
            return text;
        } catch (Throwable t) {
            return null;
        }
    }

    private Bitmap loadThumb(@NonNull View anyView, @NonNull String pathOrUri) {
        try {
            // Try as file path first
            File f = new File(pathOrUri);
            if (f.exists() && f.isFile()) {
                return ImageDecodeUtils.decodeSampled(pathOrUri, 112, 112);
            }
            // Else try as content Uri
            Uri uri = Uri.parse(pathOrUri);
            try (InputStream is = anyView.getContext().getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    opts.inSampleSize = 2;
                    return BitmapFactory.decodeStream(is, null, opts);
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }



    static class VH extends RecyclerView.ViewHolder {
        ImageView thumb;
        TextView title;
        TextView subtitle;
        TextView membership;

        VH(@NonNull View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.imageThumb);
            title = itemView.findViewById(R.id.textTitle);
            subtitle = itemView.findViewById(R.id.textSubtitle);
            membership = itemView.findViewById(R.id.textMembership);
        }
    }
}
