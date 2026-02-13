package de.schliweb.makeacopy.ui.library;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.library.LibraryServiceLocator;
import de.schliweb.makeacopy.data.library.ScanEntity;
import de.schliweb.makeacopy.utils.FeatureFlags;
import de.schliweb.makeacopy.utils.FileUtils;
import de.schliweb.makeacopy.utils.ImageDecodeUtils;

import java.text.DateFormat;
import java.util.Date;

/**
 * The ScanDetailsFragment class is a Fragment that displays the details of a specific
 * scan and provides various user interface actions for managing the scan, such as renaming,
 * deleting, sharing, or performing actions with export files. It uses a scanned entity as its
 * core data model and interacts with the scan repository and UI components to update and present
 * the scan's state.
 * <p>
 * Fields:
 * - progress: View that represents the loading progress indicator.
 * - content: View that contains the main content of the fragment.
 * - titleView: TextView that displays the title of the scan.
 * - subtitleView: TextView that displays additional information about the scan.
 * - buttonRename: Button that triggers the rename action for the scan.
 * - buttonDelete: Button that triggers the delete action for the scan.
 * - buttonShare: Button to share the scan's export file(s).
 * - buttonOpenInExport: Button to open the primary export file of the scan in an external app.
 * - buttonAddToCollection: Button to add the scan to a collection.
 * - scanId: String that uniquely identifies the scan being displayed.
 * - entity: The ScanEntity object representing metadata and details of the scan.
 * <p>
 * Methods:
 * - onCreateView: Called to create and initialize the fragment's view hierarchy. Inflates the layout
 * and initializes view components and actions.
 * - openCollectionPickerForScan: Opens a dialog or UI to select a collection to add the specified scan.
 * - loadAsync: Loads the scan details asynchronously and binds the data to the UI components.
 * - bind: Binds a ScanEntity object to the corresponding UI elements of the fragment.
 * - getPrimaryExportUri: Retrieves the primary export file URI for the scan, if available.
 * - showLoading: Toggles the visibility of the loading progress indicator.
 * - showRenameDialog: Displays a dialog allowing the user to rename the scan.
 * - confirmDelete: Shows a confirmation dialog before deleting the scan.
 * - doDelete: Deletes the scan and updates the UI.
 * - share: Shares the scan's export file using the Android sharing mechanism.
 * - openInExport: Attempts to open the scan's export file in an appropriate external application.
 */
public class ScanDetailsFragment extends Fragment {

    private View progress;
    private View content;
    private TextView titleView;
    private TextView subtitleView;
    private android.widget.ImageView previewView;
    private View buttonRename;
    private View buttonDelete;
    private View buttonShare;
    private View buttonOpenInExport;
    private View buttonAddToCollection;
    private View buttonRestoreAccess;

    // Preview navigation (for multi-page PDFs)
    private View previewNavRow;
    private android.widget.ImageButton buttonPrevPage;
    private android.widget.ImageButton buttonNextPage;
    private android.widget.TextView pageIndicatorView;
    private android.net.Uri previewPrimaryUri;
    private android.graphics.pdf.PdfRenderer pdfRenderer;
    private android.os.ParcelFileDescriptor pdfPfd;
    private int currentPageIndex = 0;
    private int totalPages = 0;

    private String scanId;
    private ScanEntity entity;

    // SAF picker for restoring access to old exports
    private androidx.activity.result.ActivityResultLauncher<String[]> restoreAccessLauncher;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_scan_details, container, false);
        progress = root.findViewById(R.id.progress);
        content = root.findViewById(R.id.content);
        titleView = root.findViewById(R.id.title);
        subtitleView = root.findViewById(R.id.subtitle);
        previewView = root.findViewById(R.id.preview);
        buttonRename = root.findViewById(R.id.buttonRename);
        buttonDelete = root.findViewById(R.id.buttonDelete);
        buttonShare = root.findViewById(R.id.buttonShare);
        buttonOpenInExport = root.findViewById(R.id.buttonOpenInExport);
        buttonAddToCollection = root.findViewById(R.id.buttonAddToCollection);
        buttonRestoreAccess = root.findViewById(R.id.buttonRestoreAccess);
        // Preview navigation controls
        previewNavRow = root.findViewById(R.id.preview_nav_row);
        buttonPrevPage = root.findViewById(R.id.buttonPrevPage);
        buttonNextPage = root.findViewById(R.id.buttonNextPage);
        pageIndicatorView = root.findViewById(R.id.textPageIndicator);
        View backButton = root.findViewById(R.id.button_back);

        // Register SAF picker for restoring access to missing export files
        restoreAccessLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) return;
                    // Try to persist read (and write) permission so the file remains accessible across restarts
                    try {
                        requireContext().getContentResolver().takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        );
                    } catch (Throwable ignore) {
                    }
                    // Prepare DB updates: primary export URI JSON and (analog) adopt picked file name as title
                    final String json = makeSingleUriArrayJson(uri);
                    String pickedName = null;
                    try {
                        pickedName = FileUtils.getDisplayNameFromUri(requireContext(), uri);
                    } catch (Throwable ignore) {
                    }
                    final String finalPickedBase;
                    if (pickedName != null && !pickedName.trim().isEmpty()) {
                        String bn = pickedName.trim();
                        int dot = bn.lastIndexOf('.');
                        if (dot > 0) bn = bn.substring(0, dot);
                        finalPickedBase = bn;
                    } else {
                        finalPickedBase = null;
                    }
                    new Thread(() -> {
                        try {
                            de.schliweb.makeacopy.data.library.ScansRepository repo =
                                    de.schliweb.makeacopy.data.library.LibraryServiceLocator.getScansRepository(requireContext());
                            repo.updateExportPathsJson(requireContext(), scanId, json);
                            if (finalPickedBase != null && !finalPickedBase.isEmpty()) {
                                // Update title to match the picked file name (without extension)
                                repo.updateTitle(requireContext(), scanId, finalPickedBase);
                            }
                        } catch (Throwable ignore) {
                        }
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(this::loadAsync);
                    }).start();
                }
        );

        // Apply system insets: place header just below the status bar and lift the bottom container above the nav bar
        final View bottomContainer = root.findViewById(R.id.button_container);
        // Keep original root paddings (do not push entire layout down)
        final int origPadLeft = root.getPaddingLeft();
        final int origPadTop = root.getPaddingTop();
        final int origPadRight = root.getPaddingRight();
        final int origPadBottom = root.getPaddingBottom();
        // Record original top margin of the header content
        final int origContentTopMargin;
        if (content != null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            origContentTopMargin = ((ViewGroup.MarginLayoutParams) content.getLayoutParams()).topMargin;
        } else {
            origContentTopMargin = 0;
        }
        final int origBottomMargin;
        if (bottomContainer != null && bottomContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            origBottomMargin = ((ViewGroup.MarginLayoutParams) bottomContainer.getLayoutParams()).bottomMargin;
        } else {
            origBottomMargin = 0;
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets sb = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            // Keep root padding unchanged
            v.setPadding(origPadLeft, origPadTop, origPadRight, origPadBottom);
            // Apply status bar inset to the header content only
            if (content != null && content.getLayoutParams() instanceof ViewGroup.MarginLayoutParams clp) {
                clp.topMargin = origContentTopMargin + sb.top;
                content.setLayoutParams(clp);
            }
            // Bottom inset for nav bar on the bottom button container
            if (bottomContainer != null && bottomContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams mlp) {
                mlp.bottomMargin = origBottomMargin + sb.bottom;
                bottomContainer.setLayoutParams(mlp);
            }
            return insets;
        });

        if (!FeatureFlags.isScanLibraryEnable()) {
            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.feature_scan_library_disabled, android.widget.Toast.LENGTH_SHORT);
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return root;
        }

        if (getArguments() != null) {
            scanId = getArguments().getString("scanId");
        }
        if (scanId == null || scanId.isEmpty()) {
            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.missing_scan_id, android.widget.Toast.LENGTH_SHORT);
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return root;
        }

        buttonRename.setOnClickListener(v -> showRenameDialog());
        buttonDelete.setOnClickListener(v -> confirmDelete());
        buttonShare.setOnClickListener(v -> share());
        buttonOpenInExport.setOnClickListener(v -> openInExport());
        if (buttonAddToCollection != null) {
            buttonAddToCollection.setOnClickListener(v -> {
                if (scanId != null) openCollectionPickerForScan(scanId);
            });
        }
        if (backButton != null) {
            backButton.setOnClickListener(v -> {
                try {
                    androidx.navigation.Navigation.findNavController(requireView()).navigateUp();
                } catch (Throwable t) {
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            });
        }

        loadAsync();
        return root;
    }

    private void openCollectionPickerForScan(@NonNull String scanId) {
        final android.content.Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            java.util.List<de.schliweb.makeacopy.data.library.CollectionEntity> cols;
            try {
                cols = de.schliweb.makeacopy.data.library.LibraryServiceLocator
                        .getCollectionsRepository(appCtx)
                        .getAllCollections(appCtx);
            } catch (Throwable t) {
                cols = java.util.Collections.emptyList();
            }
            // Filter out the default "Completed Scans" collection when adding finished documents
            java.util.List<de.schliweb.makeacopy.data.library.CollectionEntity> filtered = new java.util.ArrayList<>();
            try {
                String defName = appCtx.getString(de.schliweb.makeacopy.R.string.collection_completed_scans);
                for (de.schliweb.makeacopy.data.library.CollectionEntity c : cols) {
                    if (c == null) continue;
                    if (defName != null && defName.equals(c.name)) continue; // exclude default
                    filtered.add(c);
                }
            } catch (Throwable ignore) {
                filtered = cols; // fallback
            }
            final java.util.List<de.schliweb.makeacopy.data.library.CollectionEntity> finalCols = filtered;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                try {
                    int n = (finalCols == null) ? 0 : finalCols.size();
                    CharSequence[] names = new CharSequence[n + 1];
                    for (int i = 0; i < n; i++) {
                        names[i] = finalCols.get(i).name;
                    }
                    names[n] = getString(R.string.create_new_collection);

                    final androidx.appcompat.app.AlertDialog pickerDialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.place_in_collection_title))
                            .setItems(names, (dialog, which) -> {
                                if (which == n) {
                                    // Create new collection flow
                                    final android.widget.EditText input = new android.widget.EditText(requireContext());
                                    input.setHint(R.string.collection_name_hint);
                                    final androidx.appcompat.app.AlertDialog createDialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                            .setTitle(R.string.new_collection_title)
                                            .setView(input)
                                            .setPositiveButton(R.string.create, (d, w) -> {
                                                final String name = String.valueOf(input.getText()).trim();
                                                if (name.isEmpty()) return;
                                                new Thread(() -> {
                                                    try {
                                                        de.schliweb.makeacopy.data.library.CollectionsRepository repo = de.schliweb.makeacopy.data.library.LibraryServiceLocator.getCollectionsRepository(appCtx);
                                                        de.schliweb.makeacopy.data.library.CollectionEntity ce = repo.createCollection(appCtx, name);
                                                        if (ce != null) {
                                                            repo.assignScanToCollection(appCtx, scanId, ce.id);
                                                            if (isAdded())
                                                                requireActivity().runOnUiThread(() -> de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), getString(R.string.scan_added_to_collection, name), android.widget.Toast.LENGTH_SHORT));
                                                        }
                                                    } catch (Throwable ignore) {
                                                    }
                                                }).start();
                                            })
                                            .setNegativeButton(android.R.string.cancel, null)
                                            .create();
                                    createDialog.setOnShowListener(d -> {
                                        try {
                                            de.schliweb.makeacopy.utils.DialogUtils.improveAlertDialogButtonContrastForNight(createDialog, requireContext());
                                        } catch (Throwable ignore) {
                                        }
                                    });
                                    createDialog.show();
                                } else if (which >= 0 && which < n) {
                                    final de.schliweb.makeacopy.data.library.CollectionEntity sel = finalCols.get(which);
                                    new Thread(() -> {
                                        try {
                                            de.schliweb.makeacopy.data.library.CollectionsRepository repo = de.schliweb.makeacopy.data.library.LibraryServiceLocator.getCollectionsRepository(appCtx);
                                            repo.assignScanToCollection(appCtx, scanId, sel.id);
                                            if (isAdded())
                                                requireActivity().runOnUiThread(() -> de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), getString(R.string.scan_added_to_collection, sel.name), android.widget.Toast.LENGTH_SHORT));
                                        } catch (Throwable ignore) {
                                        }
                                    }).start();
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .create();
                    pickerDialog.setOnShowListener(d -> {
                        try {
                            de.schliweb.makeacopy.utils.DialogUtils.improveAlertDialogButtonContrastForNight(pickerDialog, requireContext());
                        } catch (Throwable ignore) {
                        }
                    });
                    pickerDialog.show();
                } catch (Throwable ignore) {
                }
            });
        }).start();
    }

    private void loadAsync() {
        showLoading(true);
        new Thread(() -> {
            ScanEntity e;
            try {
                e = LibraryServiceLocator.getScansRepository(requireContext()).getScanById(requireContext(), scanId);
            } catch (Throwable t) {
                e = null;
            }
            final ScanEntity finalE = e;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> bind(finalE));
        }).start();
    }

    private void bind(@Nullable ScanEntity e) {
        this.entity = e;
        if (e == null) {
            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.missing_file, android.widget.Toast.LENGTH_SHORT);
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return;
        }
        String title = (e.title != null && !e.title.isEmpty()) ? e.title : e.id;
        titleView.setText(title);
        String dateStr = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(e.createdAt));
        String meta = getString(R.string.collection_items_count, Math.max(1, e.pageCount)); // reuse format: "%1$d item(s)"

        // Check export URI readability to decide action enablement and hint
        android.net.Uri exportUri = getPrimaryExportUri();
        boolean canOpen = exportUri != null && FileUtils.isUriReadable(requireContext(), exportUri);
        // Derive a human-friendly folder/location of the primary export for metadata display
        String folder = deriveParentFolderDisplay(exportUri);
        StringBuilder subtitle = new StringBuilder();
        subtitle.append(dateStr).append(" • ").append(meta);
        if (folder != null && !folder.trim().isEmpty()) {
            subtitle.append(" • ").append("Folder: ").append(folder);
        }
        // Include file name in metadata when available
        try {
            String fileName = null;
            if (exportUri != null) {
                try {
                    fileName = FileUtils.getDisplayNameFromUri(requireContext(), exportUri);
                } catch (Throwable ignore) {
                }
                if (fileName == null || fileName.trim().isEmpty()) {
                    try {
                        fileName = exportUri.getLastPathSegment();
                    } catch (Throwable ignore) {
                    }
                }
            }
            if (fileName != null && !fileName.trim().isEmpty()) {
                subtitle.append(" • ").append("File: ").append(fileName);
            }
        } catch (Throwable ignore) {
        }
        if (!canOpen) {
            subtitle.append(" • ").append(getString(R.string.missing_file));
        }
        subtitleView.setText(subtitle.toString());
        if (buttonShare != null) buttonShare.setEnabled(canOpen);
        if (buttonOpenInExport != null) buttonOpenInExport.setEnabled(canOpen);
        // Offer to restore access via SAF picker when the primary export is unreadable
        if (buttonRestoreAccess != null) {
            if (!canOpen) {
                buttonRestoreAccess.setVisibility(View.VISIBLE);
                buttonRestoreAccess.setOnClickListener(v -> {
                    String[] types;
                    android.net.Uri pri = exportUri;
                    if (pri != null && isLikelyPdfUri(pri)) {
                        types = new String[]{"application/pdf"};
                    } else {
                        types = new String[]{"image/*", "application/pdf", "application/zip", "*/*"};
                    }
                    try {
                        restoreAccessLauncher.launch(types);
                    } catch (Throwable t) {
                        de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.picker_failed, android.widget.Toast.LENGTH_SHORT);
                    }
                });
            } else {
                buttonRestoreAccess.setVisibility(View.GONE);
            }
        }

        // Load a document preview between title/meta and the bottom action container
        // For CompletedScanEntry (single pages in the special collection), prioritize the original/export URI
        // over the (low-res) cover/thumbnail to avoid blurry previews. For normal finished documents,
        // keep the previous behavior: prefer coverPath for speed, then fall back to export URI.
        String source;
        boolean isCompletedScanEntry = false;
        try {
            String sm = e.sourceMetaJson;
            isCompletedScanEntry = (sm != null && sm.contains("\"CompletedScanEntry\""));
        } catch (Throwable ignore) {
            isCompletedScanEntry = false;
        }
        if (isCompletedScanEntry) {
            source = FileUtils.firstUriFromJson(e.exportPathsJson);
            if (source == null || source.isEmpty()) {
                source = e.coverPath; // fallback if no export/original available
            }
        } else {
            source = (e.coverPath != null && !e.coverPath.isEmpty()) ? e.coverPath : FileUtils.firstUriFromJson(e.exportPathsJson);
        }
        android.net.Uri primaryUri = getPrimaryExportUri();
        boolean showPdfPager = false;
        if (primaryUri != null) {
            // Decide if this looks like a PDF
            boolean looksPdf = isLikelyPdfUri(primaryUri);
            if (!looksPdf) {
                try {
                    String mime = requireContext().getContentResolver().getType(primaryUri);
                    looksPdf = (mime != null && ("application/pdf".equalsIgnoreCase(mime) || mime.toLowerCase(java.util.Locale.ROOT).contains("pdf")));
                } catch (Throwable ignore) {
                }
            }
            if (looksPdf && canOpen) {
                showPdfPager = true;
                setupPdfPreview(primaryUri);
            }
        }
        if (!showPdfPager) {
            // Fallback to static preview (image or cover); hide pager
            if (previewNavRow != null) previewNavRow.setVisibility(View.GONE);
            loadPreviewAsync(source);
        }
        showLoading(false);
    }

    private String makeSingleUriArrayJson(@NonNull android.net.Uri uri) {
        try {
            String s = uri.toString();
            String esc = s.replace("\"", "\\\"");
            return "[\"" + esc + "\"]";
        } catch (Throwable t) {
            return "[\"" + uri + "\"]";
        }
    }

    private void loadPreviewAsync(@Nullable String pathOrUri) {
        if (previewView == null) return;
        if (pathOrUri == null || pathOrUri.isEmpty()) {
            previewView.setVisibility(View.GONE);
            return;
        }
        previewView.setVisibility(View.VISIBLE);
        new Thread(() -> {
            android.graphics.Bitmap bmp = null;
            try {
                // Try as file path first
                java.io.File f = new java.io.File(pathOrUri);
                if (f.exists() && f.isFile()) {
                    bmp = ImageDecodeUtils.decodeSampled(pathOrUri, 1080, 1080);
                } else {
                    // Try as content URI
                    android.net.Uri uri = android.net.Uri.parse(pathOrUri);
                    android.content.ContentResolver cr = requireContext().getContentResolver();
                    String mime = null;
                    try {
                        mime = cr.getType(uri);
                    } catch (Throwable ignore) {
                    }
                    boolean isPdf = (mime != null && ("application/pdf".equalsIgnoreCase(mime) || mime.toLowerCase(java.util.Locale.ROOT).contains("pdf")))
                            || isLikelyPdfUri(uri);
                    if (isPdf) {
                        bmp = renderPdfFirstPage(uri, 1080);
                    } else {
                        try (java.io.InputStream is = cr.openInputStream(uri)) {
                            if (is != null) {
                                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                                opts.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                                opts.inSampleSize = 2;
                                bmp = android.graphics.BitmapFactory.decodeStream(is, null, opts);
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            final android.graphics.Bitmap finalBmp = bmp;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (previewView == null) return;
                if (finalBmp != null) {
                    previewView.setImageBitmap(finalBmp);
                    previewView.setVisibility(View.VISIBLE);
                } else {
                    previewView.setImageDrawable(null);
                    previewView.setVisibility(View.GONE);
                }
            });
        }).start();
    }



    private boolean isLikelyPdfUri(@NonNull android.net.Uri uri) {
        try {
            String s = uri.toString();
            if (s == null) return false;
            String lower = s.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".pdf") || lower.contains("/pdf");
        } catch (Throwable ignore) {
            return false;
        }
    }

    private android.graphics.Bitmap renderPdfFirstPage(@NonNull android.net.Uri uri, int targetW) {
        android.os.ParcelFileDescriptor pfd = null;
        android.graphics.pdf.PdfRenderer renderer = null;
        try {
            pfd = requireContext().getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) return null;
            renderer = new android.graphics.pdf.PdfRenderer(pfd);
            if (renderer.getPageCount() <= 0) return null;
            android.graphics.pdf.PdfRenderer.Page page = renderer.openPage(0);
            try {
                int pageW = page.getWidth();
                int pageH = page.getHeight();
                int targetH = (pageW > 0) ? Math.max(1, (int) (targetW * (pageH / (float) pageW))) : targetW;
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                canvas.drawColor(android.graphics.Color.WHITE);
                android.graphics.Matrix m = new android.graphics.Matrix();
                float scaleX = targetW / (float) pageW;
                float scaleY = targetH / (float) pageH;
                m.setScale(scaleX, scaleY);
                page.render(bmp, null, m, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } finally {
                try {
                    page.close();
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
            return null;
        } finally {
            try {
                if (renderer != null) renderer.close();
            } catch (Throwable ignore) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (Throwable ignore) {
            }
        }
    }

    @Nullable
    private android.net.Uri getPrimaryExportUri() {
        try {
            if (entity == null) return null;
            String json = entity.exportPathsJson;
            if (json == null || json.trim().isEmpty()) return null;
            org.json.JSONArray arr = new org.json.JSONArray(json);
            if (arr.length() == 0) return null;
            String s = arr.optString(0, null);
            if (s == null || s.isEmpty()) return null;
            return android.net.Uri.parse(s);
        } catch (Throwable ignore) {
            return null;
        }
    }

    @Nullable
    private String safeResolveMime(@NonNull android.net.Uri uri) {
        // Try ContentResolver first
        try {
            String mime = requireContext().getContentResolver().getType(uri);
            if (mime != null && !mime.trim().isEmpty()) return mime;
        } catch (Throwable ignore) {
        }
        // Derive from file name or path segment
        String name = null;
        try {
            name = FileUtils.getDisplayNameFromUri(requireContext(), uri);
        } catch (Throwable ignore) {
        }
        if (name == null) {
            try {
                name = uri.getLastPathSegment();
            } catch (Throwable ignore) {
            }
        }
        if (name != null) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".pdf")) return "application/pdf";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".zip")) return "application/zip";
            if (lower.endsWith(".txt")) return "text/plain";
        }
        return "*/*";
    }

    // Best-effort physical file rename via SAF DocumentsContract. Preserves the original extension.
    private void renamePrimaryExportFile(@NonNull String newBaseName) {
        try {
            android.net.Uri uri = getPrimaryExportUri();
            if (uri == null) return;
            // Determine current display name to preserve extension
            String curName = FileUtils.getDisplayNameFromUri(requireContext(), uri);
            String ext = extractExtension(curName);
            String target = (ext != null && !ext.isEmpty()) ? (newBaseName + "." + ext) : newBaseName;
            android.content.ContentResolver cr = requireContext().getContentResolver();
            android.net.Uri newUri = android.provider.DocumentsContract.renameDocument(cr, uri, target);
            if (newUri != null) {
                // Persist permission for the new Uri and update DB exportPathsJson
                try {
                    cr.takePersistableUriPermission(newUri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Throwable ignore) {
                }
                String json = makeSingleUriArrayJson(newUri);
                try {
                    de.schliweb.makeacopy.data.library.LibraryServiceLocator
                            .getScansRepository(requireContext())
                            .updateExportPathsJson(requireContext(), scanId, json);
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            // Provider may not support rename; ignore to keep operation best-effort only
        }
    }

    private static String extractExtension(@Nullable String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            return name.substring(dot + 1);
        }
        return null;
    }

    /**
     * Derives a human-friendly parent folder path for the given primary export Uri.
     * Attempts several strategies depending on the URI scheme/provider and falls back gracefully.
     */
    private String deriveParentFolderDisplay(@Nullable android.net.Uri uri) {
        if (uri == null) return null;
        try {
            // File scheme: use parent directory
            String scheme = uri.getScheme();
            if (scheme != null && scheme.equalsIgnoreCase("file")) {
                String path = uri.getPath();
                if (path != null) {
                    java.io.File f = new java.io.File(path);
                    java.io.File parent = f.getParentFile();
                    if (parent != null) return parent.getAbsolutePath();
                }
            }

            android.content.Context ctx = requireContext().getApplicationContext();
            String folder = null;

            // Try to read RELATIVE_PATH (MediaStore) when available
            android.database.Cursor c = null;
            try {
                c = ctx.getContentResolver().query(uri, new String[]{"relative_path"}, null, null, null);
                if (c != null && c.moveToFirst()) {
                    String rel = c.getString(0);
                    if (rel != null && !rel.trim().isEmpty()) {
                        folder = rel.trim();
                    }
                }
            } catch (Throwable ignore) {
            } finally {
                if (c != null) try {
                    c.close();
                } catch (Throwable ignore) {
                }
            }

            // DocumentsContract: parse documentId to infer parent path
            try {
                if (android.provider.DocumentsContract.isDocumentUri(ctx, uri)) {
                    String docId = android.provider.DocumentsContract.getDocumentId(uri);
                    if (docId != null) {
                        int colon = docId.indexOf(':');
                        String pathPart = colon >= 0 ? docId.substring(colon + 1) : docId; // e.g., Documents/MakeACopy/Scan.pdf
                        int slash = pathPart.lastIndexOf('/');
                        if (slash > 0) {
                            String parent = pathPart.substring(0, slash);
                            if (parent != null && !parent.trim().isEmpty()) {
                                if (folder == null || folder.trim().isEmpty()) folder = parent.trim();
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {
            }

            // Fallback: reconstruct from path segments (sans last segment)
            if (folder == null || folder.trim().isEmpty()) {
                java.util.List<String> segs = uri.getPathSegments();
                if (segs != null && segs.size() > 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < segs.size() - 1; i++) {
                        if (sb.length() > 0) sb.append('/');
                        sb.append(segs.get(i));
                    }
                    if (sb.length() > 0) folder = sb.toString();
                }
            }

            if (folder != null) {
                // Trim trailing slashes
                while (folder.endsWith("/")) folder = folder.substring(0, folder.length() - 1);
            }
            return (folder != null && !folder.isEmpty()) ? folder : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void showLoading(boolean show) {
        progress.setVisibility(show ? View.VISIBLE : View.GONE);
        content.setVisibility(show ? View.GONE : View.VISIBLE);
    }

    private void showRenameDialog() {
        if (entity == null) return;
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setHint(R.string.collection_name_hint);
        input.setText(entity.title != null ? entity.title : "");
        final androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.rename)
                .setView(input)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    final String name = String.valueOf(input.getText()).trim();
                    if (name.isEmpty()) return;
                    new Thread(() -> {
                        try {
                            // 1) Update title in index
                            LibraryServiceLocator.getScansRepository(requireContext()).updateTitle(requireContext(), scanId, name);
                        } catch (Throwable ignore) {
                        }
                        // Note: Only update the title in the index. Do not rename the physical file on disk.
                        if (!isAdded()) return;
                        requireActivity().runOnUiThread(this::loadAsync);
                    }).start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            try {
                de.schliweb.makeacopy.utils.DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext());
            } catch (Throwable ignore) {
            }
        });
        dialog.show();
    }

    private void confirmDelete() {
        if (entity == null) return;
        final androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.confirm)
                .setMessage(R.string.delete_warning_irreversible)
                .setPositiveButton(R.string.delete, (d, w) -> doDelete())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> {
            try {
                de.schliweb.makeacopy.utils.DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext());
            } catch (Throwable ignore) {
            }
        });
        dialog.show();
    }

    private void doDelete() {
        new Thread(() -> {
            try {
                LibraryServiceLocator.getScansRepository(requireContext()).deleteScan(requireContext(), scanId);
            } catch (Throwable ignore) {
            }
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.deleted, android.widget.Toast.LENGTH_SHORT);
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            });
        }).start();
    }

    private void share() {
        android.net.Uri uri = getPrimaryExportUri();
        if (uri == null) {
            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.missing_file, android.widget.Toast.LENGTH_SHORT);
            return;
        }
        try {
            String name = FileUtils.getDisplayNameFromUri(requireContext(), uri);
            de.schliweb.makeacopy.utils.ShareIntentHelper.shareDocument(this, uri, null, name);
        } catch (Throwable t) {
            // Fallback to a plain ACTION_SEND if helper fails
            try {
                String mime = safeResolveMime(uri);
                android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
                send.putExtra(android.content.Intent.EXTRA_STREAM, uri);
                send.setType(mime != null ? mime : "*/*");
                send.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(android.content.Intent.createChooser(send, getString(R.string.btn_share)));
            } catch (Throwable t2) {
                de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.share_failed, android.widget.Toast.LENGTH_SHORT);
            }
        }
    }

    private void openInExport() {
        android.net.Uri uri = getPrimaryExportUri();
        if (uri == null) {
            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.missing_file, android.widget.Toast.LENGTH_SHORT);
            return;
        }
        try {
            String mime = safeResolveMime(uri);
            android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            view.setDataAndType(uri, mime != null ? mime : "*/*");
            view.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(view, getString(R.string.title_export)));
        } catch (Throwable t) {
            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), R.string.no_app_found_to_open_file, android.widget.Toast.LENGTH_SHORT);
        }
    }

    // --- PDF paging helpers (multi-page preview) ---
    private void setupPdfPreview(@NonNull android.net.Uri uri) {
        closePdfRenderer();
        previewPrimaryUri = uri;
        if (previewNavRow != null) previewNavRow.setVisibility(View.GONE);
        new Thread(() -> {
            android.os.ParcelFileDescriptor pfdLocal = null;
            android.graphics.pdf.PdfRenderer rendererLocal = null;
            try {
                pfdLocal = requireContext().getContentResolver().openFileDescriptor(uri, "r");
                if (pfdLocal == null) throw new RuntimeException("PFD null");
                rendererLocal = new android.graphics.pdf.PdfRenderer(pfdLocal);
                pdfPfd = pfdLocal;
                pdfRenderer = rendererLocal;
                totalPages = Math.max(0, rendererLocal.getPageCount());
                currentPageIndex = 0;
                final android.graphics.Bitmap bmp = renderPdfPageInternal(currentPageIndex, 1080);
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (bmp != null && previewView != null) {
                        previewView.setImageBitmap(bmp);
                        previewView.setVisibility(View.VISIBLE);
                    }
                    updateNavUi();
                });
            } catch (Throwable t) {
                try {
                    if (rendererLocal != null) rendererLocal.close();
                } catch (Throwable ignore) {
                }
                try {
                    if (pfdLocal != null) pfdLocal.close();
                } catch (Throwable ignore) {
                }
                pdfRenderer = null;
                pdfPfd = null;
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    if (previewNavRow != null) previewNavRow.setVisibility(View.GONE);
                    String fallback = (entity != null && entity.coverPath != null && !entity.coverPath.isEmpty()) ? entity.coverPath : FileUtils.firstUriFromJson(entity != null ? entity.exportPathsJson : null);
                    loadPreviewAsync(fallback);
                });
            }
        }).start();
    }

    private void updateNavUi() {
        try {
            if (previewNavRow == null) return;
            boolean show = (pdfRenderer != null && totalPages > 1);
            previewNavRow.setVisibility(show ? View.VISIBLE : View.GONE);
            if (!show) return;
            if (pageIndicatorView != null) {
                pageIndicatorView.setText((currentPageIndex + 1) + "/" + totalPages);
            }
            if (buttonPrevPage != null) {
                buttonPrevPage.setEnabled(currentPageIndex > 0);
                buttonPrevPage.setOnClickListener(v -> goToPage(currentPageIndex - 1));
            }
            if (buttonNextPage != null) {
                buttonNextPage.setEnabled(currentPageIndex < totalPages - 1);
                buttonNextPage.setOnClickListener(v -> goToPage(currentPageIndex + 1));
            }
        } catch (Throwable ignore) {
        }
    }

    private void goToPage(int index) {
        if (pdfRenderer == null) return;
        if (index < 0 || index >= totalPages) return;
        final int target = index;
        new Thread(() -> {
            final android.graphics.Bitmap bmp = renderPdfPageInternal(target, 1080);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                currentPageIndex = target;
                if (bmp != null && previewView != null) {
                    previewView.setImageBitmap(bmp);
                    previewView.setVisibility(View.VISIBLE);
                }
                updateNavUi();
            });
        }).start();
    }

    private android.graphics.Bitmap renderPdfPageInternal(int pageIndex, int targetW) {
        try {
            if (pdfRenderer == null) return null;
            if (pageIndex < 0 || pageIndex >= pdfRenderer.getPageCount()) return null;
            android.graphics.pdf.PdfRenderer.Page page = pdfRenderer.openPage(pageIndex);
            try {
                int pageW = page.getWidth();
                int pageH = page.getHeight();
                int targetH = (pageW > 0) ? Math.max(1, (int) (targetW * (pageH / (float) pageW))) : targetW;
                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                canvas.drawColor(android.graphics.Color.WHITE);
                android.graphics.Matrix m = new android.graphics.Matrix();
                float scaleX = targetW / (float) pageW;
                float scaleY = targetH / (float) pageH;
                m.setScale(scaleX, scaleY);
                page.render(bmp, null, m, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } finally {
                try {
                    page.close();
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private void closePdfRenderer() {
        try {
            if (pdfRenderer != null) pdfRenderer.close();
        } catch (Throwable ignore) {
        }
        try {
            if (pdfPfd != null) pdfPfd.close();
        } catch (Throwable ignore) {
        }
        pdfRenderer = null;
        pdfPfd = null;
        totalPages = 0;
        currentPageIndex = 0;
        previewPrimaryUri = null;
    }

    @Override
    public void onDestroyView() {
        closePdfRenderer();
        super.onDestroyView();
    }
}
