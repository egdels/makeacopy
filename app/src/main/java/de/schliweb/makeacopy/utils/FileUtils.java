package de.schliweb.makeacopy.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

/**
 * A utility class providing functionality to interact with files and URIs, including methods
 * for extracting display names from URIs. This class is designed to accommodate different schemes
 * such as "content" and "file", and provides fallback mechanisms for unrecognized schemes.
 * <p>
 * This class is not intended to be instantiated.
 */
public class FileUtils {
    private static final String TAG = "FileUtils";

    private FileUtils() {
        // private because utility class
    }

    /**
     * Extracts and returns the display name from the given URI. Handles URIs with content
     * and file schemes, and falls back to returning the URI string if the display name cannot
     * be determined.
     *
     * @param context The application context used to access resources and content providers.
     * @param uri     The URI from which to extract the display name.
     * @return The extracted display name, or the URI string if the display name cannot be determined.
     */
    public static String getDisplayNameFromUri(Context context, Uri uri) {
        if (uri == null) {
            Log.d(TAG, "getDisplayNameFromUri: URI is null");
            return null;
        }

        Log.d(TAG, "getDisplayNameFromUri: Input URI: " + uri);
        Log.d(TAG, "getDisplayNameFromUri: URI scheme: " + uri.getScheme());

        String result = null;

        // For content scheme URIs, try to query the content provider for the display name
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            Log.d(TAG, "getDisplayNameFromUri: Processing content:// URI");
            try {
                // Try to get the display name from the OpenableColumns
                Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);

                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        String displayName = cursor.getString(nameIndex);
                        Log.d(TAG, "getDisplayNameFromUri: Found display name from cursor: " + displayName);
                        cursor.close();
                        result = displayName;
                    } else {
                        Log.d(TAG, "getDisplayNameFromUri: DISPLAY_NAME column not found in cursor");
                        cursor.close();
                    }
                } else {
                    Log.d(TAG, "getDisplayNameFromUri: Cursor is null or empty");
                    if (cursor != null) cursor.close();
                }

                // If we couldn't get the display name, try to get it from the last path segment
                if (result == null) {
                    String lastPathSegment = uri.getLastPathSegment();
                    Log.d(TAG, "getDisplayNameFromUri: Last path segment: " + lastPathSegment);
                    if (lastPathSegment != null) {
                        result = lastPathSegment;
                        Log.d(TAG, "getDisplayNameFromUri: Using last path segment as filename: " + result);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting display name from content URI", e);
            }
        }
        // For file scheme URIs, extract the filename from the path
        else if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            Log.d(TAG, "getDisplayNameFromUri: Processing file:// URI");
            String path = uri.getPath();
            Log.d(TAG, "getDisplayNameFromUri: File path: " + path);
            if (path != null) {
                int lastSlashIndex = path.lastIndexOf('/');
                if (lastSlashIndex != -1 && lastSlashIndex < path.length() - 1) {
                    result = path.substring(lastSlashIndex + 1);
                    Log.d(TAG, "getDisplayNameFromUri: Extracted filename from path: " + result);
                } else {
                    Log.d(TAG, "getDisplayNameFromUri: Could not extract filename from path");
                }
            }
        } else {
            Log.d(TAG, "getDisplayNameFromUri: Unknown URI scheme: " + uri.getScheme());
        }

        // If all else fails, return the URI string
        if (result == null) {
            result = uri.toString();
            Log.d(TAG, "getDisplayNameFromUri: Falling back to URI string: " + result);
        }

        Log.d(TAG, "getDisplayNameFromUri: Final result: " + result);
        return result;
    /**
     * Checks if a URI is readable without actually reading its content.
     * This is a lightweight check that attempts to open the URI for reading and immediately closes it.
     *
     * @param context The application context used to access the content resolver.
     * @param uri The URI to check for readability.
     * @return true if the URI can be opened for reading, false otherwise.
     */
    public static boolean isUriReadable(Context context, Uri uri) {
        if (context == null || uri == null) return false;
        try {
            ContentResolver cr = context.getContentResolver();
            try (android.os.ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
                if (pfd != null) return true;
            } catch (Throwable ignore) {
                // fallback to stream
                try (java.io.InputStream is = cr.openInputStream(uri)) {
                    return is != null;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    /**
     * Checks if a URI string is readable without actually reading its content.
     * This is a lightweight check that attempts to open the URI for reading and immediately closes it.
     *
     * @param context The application context used to access the content resolver.
     * @param uriString The URI string to check for readability.
     * @return true if the URI can be opened for reading, false otherwise.
     */
    public static boolean isUriReadable(Context context, String uriString) {
        if (context == null || uriString == null || uriString.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(uriString);
            return isUriReadable(context, uri);
        } catch (Throwable ignore) {
        }
        return false;
    }

    /**
     * Extracts the first URI string from a simple JSON array representation.
     * This is a lightweight parser for arrays like ["content://..."] or ["file://..."]
     * without requiring a full JSON library dependency.
     *
     * @param json The JSON string to parse, expected to be a JSON array containing URI strings.
     * @return The first URI string found in the array, or null if none is found or if the input is invalid.
     */
    public static String firstUriFromJson(String json) {
        if (json == null) return null;
        try {
            // Very small, dependency-free parser: find first quoted string in a JSON array
            int i = json.indexOf('"');
            while (i >= 0 && i + 1 < json.length()) {
                if (i > 0 && json.charAt(i - 1) == '\\') { // skip escaped quotes
                    i = json.indexOf('"', i + 1);
                    continue;
                }
                int j = json.indexOf('"', i + 1);
                while (j > i && json.charAt(j - 1) == '\\') {
                    j = json.indexOf('"', j + 1);
                }
                if (j > i) {
                    return json.substring(i + 1, j);
                } else {
                    break;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }
}