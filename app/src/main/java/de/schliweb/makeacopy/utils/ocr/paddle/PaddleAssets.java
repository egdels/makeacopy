/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr.paddle;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A utility class for managing and extracting PaddleOCR-related assets.
 * Handles verification, caching, and extraction of required model and dictionary
 * files from the assets directory to the application's cache.
 *
 * This class is designed to ensure model availability while maintaining integrity
 * checks using SHA256 hashes.
 */
final class PaddleAssets {

    private static final String TAG = "PaddleAssets";

    static final String ASSET_DIR = "paddleocr/v5";
    static final String CACHE_SUBDIR = "paddleocr/v5";

    static final String DET_NAME = "det.ort";
    static final String SHA256SUMS_NAME = "SHA256SUMS";

    private PaddleAssets() {
        // no instances
    }

    // ---------------------------------------------------------------
    // SHA256SUMS-Cache (asset-name → expected lower-case hex hash).
    // Gefüllt beim ersten Zugriff; identisch für die gesamte Prozesslaufzeit.
    // ---------------------------------------------------------------
    private static volatile Map<String, String> assetSha256Map;

    @VisibleForTesting
    static synchronized Map<String, String> loadAssetSha256Map(Context context) throws IOException {
        Map<String, String> cached = assetSha256Map;
        if (cached != null) return cached;
        Map<String, String> m = new LinkedHashMap<>();
        try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + SHA256SUMS_NAME);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                // Format: "<sha256>  <filename>" (zwei Leerzeichen wie bei sha256sum(1)).
                int sp = trimmed.indexOf(' ');
                if (sp != 64 || trimmed.length() < 66) continue;
                String hash = trimmed.substring(0, 64).toLowerCase(Locale.ROOT);
                String name = trimmed.substring(sp).trim();
                m.put(name, hash);
            }
        }
        cached = Collections.unmodifiableMap(m);
        assetSha256Map = cached;
        return cached;
    }

    // ---------------------------------------------------------------
    // Public API (paket-privat) — Multi-Model
    // ---------------------------------------------------------------

    /**
     * Ensures that the detection model assets are extracted to the cache directory.
     * If the assets are already extracted and valid, this method does nothing.
     * Otherwise, it extracts the detection model asset and verifies its integrity.
     *
     * @param context the context used to access the assets and cache directory
     * @throws IOException if the context is null, if a forbidden .onnx asset is found,
     *                     or if there is an issue with extraction or asset integrity
     */
    static synchronized void ensureDetExtracted(Context context) throws IOException {
        if (context == null) throw new IOException("context is null");
        rejectOnnxAssetsOrThrow(context);
        File outDir = cacheDir(context);
        extractRaw(context, DET_NAME, outDir, /*expectedSha=*/null);
    }

    /**
     * Ensures that the recognition model assets are extracted to the cache directory.
     * If the assets are already extracted and valid, this method does nothing.
     * Otherwise, it extracts the specified recognition model assets and verifies their integrity.
     *
     * @param context the context used to access the assets and cache directory
     * @param modelKey the key identifying the recognition model whose assets need to be extracted
     * @throws IOException if the context is null, if the modelKey is null, if the modelKey is unknown,
     *                     if a forbidden .onnx asset is found, or if there is an issue with extraction or asset integrity
     */
    static synchronized void ensureRecExtracted(Context context, String modelKey) throws IOException {
        if (context == null) throw new IOException("context is null");
        if (modelKey == null) throw new IOException("modelKey is null");
        String base = PaddleLanguageRouter.assetBaseName(modelKey);
        if (base == null) throw new IOException("Unknown rec modelKey: " + modelKey);
        rejectOnnxAssetsOrThrow(context);
        File outDir = cacheDir(context);
        Map<String, String> shaMap = loadAssetSha256Map(context);
        String dictName = base + "_dict.txt";
        String ortName = base + ".ort";
        String dictExpected = shaMap.get(dictName);
        if (dictExpected == null) {
            // SHA256SUMS muss alle Dicts pinnen — Mismatch ist Build-Bug.
            throw new IOException("Missing SHA256 entry for asset: " + dictName);
        }
        extractRaw(context, dictName, outDir, dictExpected);
        // .ort: kein pinned SHA verfügbar → nur Existenz/Größe.
        extractRaw(context, ortName, outDir, /*expectedSha=*/null);
    }

    /**
     * Checks whether the required model assets for the specified model key are present.
     * It verifies the existence of the detection asset and recognition model assets
     * (e.g., model file and dictionary file) specific to the provided model key.
     *
     * @param context the context used to access the assets
     * @param modelKey the key identifying the recognition model whose assets need to be checked
     * @return {@code true} if the model assets are present and valid; {@code false} otherwise
     */
    static boolean areModelsPresent(Context context, String modelKey) {
        try {
            if (!getDetAssetExists(context)) return false;
            String base = PaddleLanguageRouter.assetBaseName(modelKey);
            if (base == null) return false;
            return assetExists(context, base + ".ort")
                    && assetExists(context, base + "_dict.txt");
        } catch (Throwable t) {
            Log.w(TAG, "areModelsPresent failed", t);
            return false;
        }
    }

    /**
     * Checks if any recognition model is present in the application's assets.
     * The method iterates through all predefined model keys, verifies the asset base name,
     * and checks for the existence of the corresponding model file (.ort).
     * Logs a warning if an exception occurs during execution.
     *
     * @param context the context used to access the application's assets
     * @return {@code true} if at least one recognition model is found; {@code false} otherwise
     */
    static boolean anyRecModelPresent(Context context) {
        try {
            for (String mk : PaddleLanguageRouter.MODEL_KEYS) {
                String base = PaddleLanguageRouter.assetBaseName(mk);
                if (base != null && assetExists(context, base + ".ort")) return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "anyRecModelPresent failed", t);
        }
        return false;
    }

    /**
     * Returns a {@link File} object representing the detection model file in the cache directory.
     *
     * @param context the context used to access the application's cache directory
     * @return a {@link File} object pointing to the detection model file
     */
    static File getDetModelFile(Context context) {
        return new File(cacheDir(context), DET_NAME);
    }

    /**
     * Returns a {@link File} object representing the recognition model file
     * in the application's cache directory based on the provided model key.
     *
     * This method uses the {@code modelKey} to determine the base name of the recognition
     * model file and appends the ".ort" extension to it. The resulting file is located
     * in the cache directory of the given context.
     *
     * @param context the context used to access the application's cache directory
     * @param modelKey the key identifying the recognition model
     * @return a {@link File} object pointing to the recognition model file in the cache directory
     * @throws IllegalArgumentException if the {@code modelKey} is unrecognized
     */
    static File getRecModelFile(Context context, String modelKey) {
        String base = requireBase(modelKey);
        return new File(cacheDir(context), base + ".ort");
    }

    /**
     * Returns a {@link File} object representing the dictionary file for the recognition model
     * in the application's cache directory based on the provided model key.
     *
     * This method uses the {@code modelKey} to determine the base name of the dictionary file and
     * appends the "_dict.txt" extension to it. The resulting file is located in the cache directory
     * of the given context.
     *
     * @param context the context used to access the application's cache directory
     * @param modelKey the key identifying the recognition model
     * @return a {@link File} object pointing to the dictionary file for the recognition model
     *         in the cache directory
     * @throws IllegalArgumentException if the {@code modelKey} is unrecognized
     */
    static File getRecDictFile(Context context, String modelKey) {
        String base = requireBase(modelKey);
        return new File(cacheDir(context), base + "_dict.txt");
    }

    // ---------------------------------------------------------------
    // Pure helpers
    // ---------------------------------------------------------------

    /**
     * Computes the SHA-256 hash of the provided byte array and returns it as a
     * lower-case hexadecimal string.
     *
     * @param data the byte array whose SHA-256 hash is to be computed; must not be null
     * @return the lower-case hexadecimal representation of the computed SHA-256 hash
     * @throws IllegalStateException if the SHA-256 algorithm is not available on the platform
     */
    static String sha256OfBytes(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Computes the SHA-256 hash of the contents of the specified file and returns it
     * as a lower-case hexadecimal string.
     *
     * @param file the file whose SHA-256 hash is to be computed; must not be null
     * @return the lower-case hexadecimal representation of the computed SHA-256 hash
     * @throws IOException if an I/O error occurs while reading the file
     * @throws IllegalStateException if the SHA-256 algorithm is not available on the platform
     */
    static String sha256OfFile(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return toHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Verifies that the SHA-256 hash of the provided data matches the expected hash.
     * If the hashes do not match, an {@link IOException} is thrown with a message
     * indicating the mismatch and the provided label.
     *
     * @param data the byte array of data whose SHA-256 hash will be computed; must not be null
     * @param expectedSha256 the expected SHA-256 hash represented as a string; must not be null
     * @param label a descriptive label for the data being verified; used in exception messages
     * @throws IOException if the computed SHA-256 hash does not match the expected hash
     */
    static void verifyOrThrow(byte[] data, String expectedSha256, String label) throws IOException {
        String actual = sha256OfBytes(data);
        if (!actual.equalsIgnoreCase(expectedSha256)) {
            throw new IOException("SHA256 mismatch for " + label
                    + ": expected=" + expectedSha256 + " actual=" + actual);
        }
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    private static String requireBase(String modelKey) {
        String base = PaddleLanguageRouter.assetBaseName(modelKey);
        if (base == null) {
            throw new IllegalArgumentException("Unknown rec modelKey: " + modelKey);
        }
        return base;
    }

    private static File cacheDir(Context context) {
        File outDir = new File(context.getCacheDir(), CACHE_SUBDIR);
        if (!outDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
        }
        return outDir;
    }

    private static boolean assetExists(Context context, String name) throws IOException {
        Set<String> known = listAssetEntries(context);
        return known.contains(name);
    }

    private static boolean getDetAssetExists(Context context) throws IOException {
        return assetExists(context, DET_NAME);
    }

    private static volatile Set<String> assetEntryCache;

    private static Set<String> listAssetEntries(Context context) throws IOException {
        Set<String> c = assetEntryCache;
        if (c != null) return c;
        synchronized (PaddleAssets.class) {
            c = assetEntryCache;
            if (c == null) {
                String[] arr = context.getAssets().list(ASSET_DIR);
                Set<String> s = new HashSet<>();
                if (arr != null) Collections.addAll(s, arr);
                c = Collections.unmodifiableSet(s);
                assetEntryCache = c;
            }
        }
        return c;
    }

    private static void rejectOnnxAssetsOrThrow(Context context) throws IOException {
        for (String name : listAssetEntries(context)) {
            if (name.endsWith(".onnx")) {
                throw new IOException(
                        "Forbidden .onnx asset present in " + ASSET_DIR + "/: " + name
                                + " — only .ort models are allowed.");
            }
        }
    }

    /**
     * Extracts a raw asset file from the application's assets directory to the specified output directory.
     * If the file already exists and a hash is provided, it validates the file's hash against the expected value.
     * If the existing file does not match the expected hash or if extraction is required, the method extracts the file.
     *
     * @param context     The application context used to access the assets directory.
     * @param name        The name of the asset file to extract.
     * @param outDir      The output directory where the extracted file will be placed.
     * @param expectedSha The expected SHA-256 hash of the file for validation. Can be null if hash validation is not required.
     * @throws IOException If an I/O error occurs during extraction or if hash validation fails.
     */
    private static void extractRaw(Context context, String name, File outDir,
                                   @Nullable String expectedSha) throws IOException {
        File target = new File(outDir, name);
        if (target.isFile() && target.length() > 0) {
            if (expectedSha == null) {
                return;
            }
            try {
                String have = sha256OfFile(target);
                if (have.equalsIgnoreCase(expectedSha)) return;
                Log.w(TAG, "Cached " + name + " hash mismatch, re-extracting");
            } catch (IOException ioe) {
                Log.w(TAG, "Could not hash cached " + name + ", re-extracting", ioe);
            }
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
        File tmp = new File(outDir, name + ".tmp");
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        String assetPath = ASSET_DIR + "/" + name;
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        }
        if (expectedSha != null) {
            String actual = sha256OfFile(tmp);
            if (!actual.equalsIgnoreCase(expectedSha)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException("SHA256 mismatch for asset " + assetPath
                        + ": expected=" + expectedSha + " actual=" + actual);
            }
        }
        if (tmp.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Empty asset extracted: " + assetPath);
        }
        if (!tmp.renameTo(target)) {
            try (InputStream in = new FileInputStream(tmp);
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    @VisibleForTesting
    static synchronized void resetAssetCachesForTests() {
        assetSha256Map = null;
        assetEntryCache = null;
    }

    /**
     * Creates and returns a copy of the SHA-256 asset map for the given context.
     *
     * @param context the Android context from which the assets should be loaded.
     * @return a new map containing a copy of the asset names as keys and their corresponding SHA-256 hashes as values.
     * @throws IOException if an error occurs while loading the asset map.
     */
    @VisibleForTesting
    static Map<String, String> assetSha256MapCopy(Context context) throws IOException {
        return new HashMap<>(loadAssetSha256Map(context));
    }
}
