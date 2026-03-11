package de.schliweb.makeacopy.utils.export;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import de.schliweb.makeacopy.ui.export.ExportPrefsHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for facilitating the sharing of documents through intents. This class provides
 * methods to handle and construct sharing intents for document files, accommodating multiple file
 * formats and additional contents.
 *
 * <p>The class is not intended to be instantiated.
 */
public final class ShareIntentHelper {
  private ShareIntentHelper() {}

  /**
   * Shares a document along with an optional OCR text file through an intent chooser. This method
   * supports PDF, JPEG, and ZIP files, and optionally allows attaching an OCR text file in plain
   * text format.
   *
   * @param fragment The Fragment from which the sharing operation is initiated. This is required to
   *     obtain the context and to start the sharing intent.
   * @param documentUri The URI of the main document that needs to be shared. This should point to a
   *     valid file to be shared.
   * @param txtUri The URI of an optional OCR text file to be shared alongside the main document.
   *     Can be null if no text file is to be included.
   * @param fileName The name of the file being shared. The file extension is used to deduce the
   *     MIME type (e.g., ".pdf", ".jpg", ".zip"). If null or empty, the MIME type defaults to
   *     "application/pdf".
   */
  public static void shareDocument(
      Fragment fragment, Uri documentUri, Uri txtUri, String fileName) {
    if (fragment == null || documentUri == null) return;
    Context ctx = fragment.requireContext();

    String lower = fileName != null ? fileName.toLowerCase(java.util.Locale.ROOT) : "";
    boolean isJpg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    boolean isZip = lower.endsWith(".zip");
    String primaryMime = isZip ? "application/zip" : (isJpg ? "image/jpeg" : "application/pdf");

    boolean hasTxt = (txtUri != null);

    final Intent intent = new Intent(hasTxt ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);

    if (hasTxt) {
      intent.setType("*/*");
      intent.putExtra(
          Intent.EXTRA_MIME_TYPES,
          new String[] {"application/pdf", "image/jpeg", "application/zip", "text/plain"});
    } else {
      intent.setType(primaryMime);
    }

    Uri contentDoc = ensureContentUri(ctx, documentUri);

    String label = hasTxt ? (fileName + " + OCR TXT") : fileName;
    if (label == null) label = "Document";

    // When "attach only" is enabled, omit subject/body/title so the user composes the email
    // themselves.
    if (!ExportPrefsHelper.isShareAttachOnly(ctx)) {
      intent.putExtra(Intent.EXTRA_TITLE, label);
      intent.putExtra(Intent.EXTRA_SUBJECT, label);
      intent.putExtra(Intent.EXTRA_TEXT, label);
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

    if (hasTxt) {
      Uri contentTxt = ensureContentUri(ctx, txtUri);

      ArrayList<Uri> streams = new ArrayList<>(2);
      streams.add(contentDoc);
      streams.add(contentTxt);
      intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);

      ClipData clip =
          new ClipData("attachments", new String[] {"*/*"}, new ClipData.Item(contentDoc));
      clip.addItem(new ClipData.Item(contentTxt));
      intent.setClipData(clip);

    } else {
      intent.putExtra(Intent.EXTRA_STREAM, contentDoc);
      ClipData clip = ClipData.newUri(ctx.getContentResolver(), label, contentDoc);
      intent.setClipData(clip);
    }

    PackageManager pm = ctx.getPackageManager();
    List<ResolveInfo> targets = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
    for (ResolveInfo ri : targets) {
      String pkg = ri.activityInfo.packageName;
      ctx.grantUriPermission(
          pkg, ensureContentUri(ctx, documentUri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
      if (hasTxt) {
        ctx.grantUriPermission(
            pkg, ensureContentUri(ctx, txtUri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
      }
    }

    fragment.startActivity(Intent.createChooser(intent, "Share " + label));
  }

  /**
   * Ensures that the given URI has a "content" scheme. If the URI is already in the "content"
   * scheme, it is returned as is. If the URI is in a different scheme, such as "file", it is
   * converted to a "content" URI using a FileProvider.
   *
   * @param ctx The context used to resolve the FileProvider and convert the file URI to a content
   *     URI.
   * @param uri The URI to be validated or converted. If this is null, the method will return null.
   * @return A content URI corresponding to the input URI. Returns the original URI if it already
   *     has the "content" scheme, or null if the input URI is null.
   */
  private static Uri ensureContentUri(Context ctx, Uri uri) {
    if (uri == null) return null;
    if ("content".equalsIgnoreCase(uri.getScheme())) return uri;
    String authority = ctx.getPackageName() + ".fileprovider";
    File file = new File(uri.getPath());
    return FileProvider.getUriForFile(ctx, authority, file);
  }
}
