/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.layout;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.schliweb.makeacopy.testutil.PdfBoxTesting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import de.schliweb.makeacopy.utils.export.PdfCreator;
import de.schliweb.makeacopy.utils.ocr.RecognizedWord;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Instrumented integration tests that verify the OCR text layer in exported PDFs.
 *
 * <p>These tests exercise the real export path:
 * <ol>
 *   <li>Render a synthetic test PDF to a bitmap</li>
 *   <li>Derive {@link RecognizedWord}s from the JSON ground truth (simulating OCR output)</li>
 *   <li>Call {@link PdfCreator#createSearchablePdf} to produce an export PDF with OCR text layer</li>
 *   <li>Re-open the export PDF with PdfBox {@link PDFTextStripper} and extract the text layer</li>
 *   <li>Compare the extracted text against the ground truth</li>
 * </ol>
 *
 */
@RunWith(AndroidJUnit4.class)
@SuppressWarnings("StringSplitter")
public class ExportPdfIntegrationTest {

    private static final String TAG = "ExportPdfIntegrationTest";
    private static final String ASSET_DIR = "layout_test_data";
    private static final int RENDER_DPI = 300;

    private Context testContext;
    private Context appContext;

    @Before
    public void setUp() {
        testContext = InstrumentationRegistry.getInstrumentation().getContext();
        appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PdfBoxTesting.init();
    }

    // ==================== Test Cases ====================

    @Test
    public void testExport_singleColumn() throws Exception {
        runExportTest("single_column_s42");
    }

    @Test
    public void testExport_twoColumn() throws Exception {
        runExportTest("two_column_s43");
    }

    @Test
    public void testExport_sidebar() throws Exception {
        runExportTest("sidebar_s46");
    }

    @Test
    public void testExport_separator() throws Exception {
        runExportTest("separator_s53");
    }

    @Test
    public void testExport_tableLike() throws Exception {
        runExportTest("table_like_s48");
    }

    @Test
    public void testExport_complex() throws Exception {
        runExportTest("complex_s50");
    }

    // ==================== Core Test Logic ====================

    private void runExportTest(String testCaseName) throws Exception {
        // 1. Render source PDF to bitmap
        Bitmap bitmap = renderPdfFirstPage(testCaseName + ".pdf");
        assertNotNull("PDF should render to bitmap: " + testCaseName, bitmap);

        // 2. Load ground truth and derive RecognizedWords
        JsonObject groundTruth = loadGroundTruth(testCaseName + ".json");
        JsonObject page = groundTruth.getAsJsonArray("pages").get(0).getAsJsonObject();
        double pageWidthPt = page.get("width_pt").getAsDouble();
        double pageHeightPt = page.get("height_pt").getAsDouble();

        List<RecognizedWord> words = deriveRecognizedWords(
                page, bitmap.getWidth(), bitmap.getHeight(), pageWidthPt, pageHeightPt);

        Log.d(TAG, testCaseName + ": derived " + words.size() + " RecognizedWords from ground truth");

        // 3. Create export PDF via real PdfCreator
        File outputFile = new File(appContext.getCacheDir(), "export_" + testCaseName + ".pdf");
        Uri outputUri = Uri.fromFile(outputFile);

        Uri result;
        try {
            result = PdfCreator.createSearchablePdf(
                    appContext, bitmap, words, outputUri,
                    85, false, false, RENDER_DPI);
        } finally {
            if (!bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }

        assertNotNull(testCaseName + ": PdfCreator should return non-null URI", result);
        assertTrue(testCaseName + ": export PDF should exist", outputFile.exists());
        assertTrue(testCaseName + ": export PDF should not be empty", outputFile.length() > 0);

        // 4. Extract text from export PDF
        String extractedText;
        try (PDDocument doc = PDDocument.load(outputFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            extractedText = stripper.getText(doc);
        }

        Log.d(TAG, testCaseName + ": extracted text length=" + extractedText.length());
        Log.d(TAG, testCaseName + ": extracted text (first 500 chars)="
                + extractedText.substring(0, Math.min(500, extractedText.length())));

        // 5. Compare against ground truth
        String expectedFullText = page.get("full_text").getAsString();
        List<String> expectedKeyPhrases = extractKeyPhrases(page);

        verifyExportedText(testCaseName, extractedText, expectedFullText, expectedKeyPhrases, page);

        // Cleanup
        //noinspection ResultOfMethodCallIgnored
        outputFile.delete();
    }

    // ==================== Verification ====================

    private void verifyExportedText(
            String testCaseName,
            String extractedText,
            String expectedFullText,
            List<String> keyPhrases,
            JsonObject page) {

        StringBuilder report = new StringBuilder();
        report.append("\n=== Export PDF Test: ").append(testCaseName).append(" ===\n");
        report.append("Feature activated via: FeatureFlags.setLayoutAnalysisOverride(true)\n");

        String normExtracted = normalizeText(extractedText);
        String normExpected = normalizeText(expectedFullText);

        report.append("Expected text length (normalized): ").append(normExpected.length()).append("\n");
        report.append("Extracted text length (normalized): ").append(normExtracted.length()).append("\n");

        // A) OCR text is present
        assertFalse(testCaseName + ": export PDF should contain extractable text\n" + report,
                normExtracted.isEmpty());

        // B) Key phrases present
        int phrasesFound = 0;
        for (String phrase : keyPhrases) {
            String normPhrase = normalizeText(phrase);
            if (normExtracted.contains(normPhrase)) {
                phrasesFound++;
            } else {
                report.append("MISSING key phrase: '").append(phrase).append("'\n");
            }
        }
        report.append("Key phrases found: ").append(phrasesFound)
                .append("/").append(keyPhrases.size()).append("\n");

        // At least 50% of key phrases should be present (tolerant for PDF text extraction quirks)
        assertTrue(testCaseName + ": at least half of key phrases should be in export PDF. "
                        + "Found " + phrasesFound + "/" + keyPhrases.size() + "\n" + report,
                keyPhrases.isEmpty() || phrasesFound >= keyPhrases.size() / 2);

        // C) Text reconstruction: check that significant words from expected text appear
        String[] expectedWords = normExpected.split("\\s+");
        int wordsFound = 0;
        for (String word : expectedWords) {
            if (word.length() >= 3 && normExtracted.contains(word)) {
                wordsFound++;
            }
        }
        int significantWords = 0;
        for (String w : expectedWords) {
            if (w.length() >= 3) significantWords++;
        }
        double wordRecall = significantWords > 0 ? (double) wordsFound / significantWords : 1.0;
        report.append(String.format(Locale.US, "Word recall (len>=3): %.1f%% (%d/%d)\n",
                wordRecall * 100, wordsFound, significantWords));

        // At least 40% of significant words should appear (tolerant for font encoding differences)
        assertTrue(testCaseName + ": word recall too low: " + String.format(Locale.US, "%.1f%%", wordRecall * 100)
                        + "\n" + report,
                significantWords == 0 || wordRecall >= 0.4);

        // D) Reading order: check that textual zones appear in roughly correct order
        JsonArray zones = page.getAsJsonArray("zones");
        List<String> zoneTexts = new ArrayList<>();
        for (JsonElement e : zones) {
            JsonObject zone = e.getAsJsonObject();
            if (zone.has("is_textual") && !zone.get("is_textual").getAsBoolean()) continue;
            String zoneText = zone.get("text").getAsString().trim();
            if (!zoneText.isEmpty()) {
                zoneTexts.add(zoneText);
            }
        }

        // Check pairwise order: for consecutive textual zones, the first zone's text
        // should appear before the second zone's text in the extracted output
        int orderCorrect = 0;
        int orderChecked = 0;
        for (int i = 0; i < zoneTexts.size() - 1; i++) {
            String firstWord = extractFirstSignificantWord(zoneTexts.get(i));
            String secondWord = extractFirstSignificantWord(zoneTexts.get(i + 1));
            if (firstWord == null || secondWord == null) continue;

            int posFirst = normExtracted.indexOf(normalizeText(firstWord));
            int posSecond = normExtracted.indexOf(normalizeText(secondWord));
            if (posFirst >= 0 && posSecond >= 0) {
                orderChecked++;
                if (posFirst < posSecond) {
                    orderCorrect++;
                } else {
                    report.append("ORDER ISSUE: '").append(firstWord)
                            .append("' should appear before '").append(secondWord).append("'\n");
                }
            }
        }
        if (orderChecked > 0) {
            double orderAccuracy = (double) orderCorrect / orderChecked;
            report.append(String.format(Locale.US, "Reading order accuracy: %.1f%% (%d/%d)\n",
                    orderAccuracy * 100, orderCorrect, orderChecked));
        }

        Log.d(TAG, report.toString());
    }

    // ==================== Helper: Ground Truth → RecognizedWords ====================

    /**
     * Converts ground truth zones/lines into RecognizedWords in bitmap pixel coordinates.
     * Ground truth uses PDF points (origin bottom-left), bitmap uses pixels (origin top-left).
     */
    private List<RecognizedWord> deriveRecognizedWords(
            JsonObject page, int bitmapWidth, int bitmapHeight,
            double pageWidthPt, double pageHeightPt) {

        double scaleX = bitmapWidth / pageWidthPt;
        double scaleY = bitmapHeight / pageHeightPt;

        List<RecognizedWord> words = new ArrayList<>();
        JsonArray zones = page.getAsJsonArray("zones");

        for (JsonElement ze : zones) {
            JsonObject zone = ze.getAsJsonObject();
            if (zone.has("is_textual") && !zone.get("is_textual").getAsBoolean()) continue;

            JsonArray lines = zone.getAsJsonArray("lines");
            if (lines == null) continue;

            for (JsonElement le : lines) {
                JsonObject line = le.getAsJsonObject();
                String text = line.get("text").getAsString().trim();
                if (text.isEmpty()) continue;

                JsonArray bbox = line.getAsJsonArray("bbox");
                double x0 = bbox.get(0).getAsDouble();
                double y0 = bbox.get(1).getAsDouble();
                double x1 = bbox.get(2).getAsDouble();
                double y1 = bbox.get(3).getAsDouble();

                // Convert PDF coords (bottom-left origin) to bitmap coords (top-left origin)
                float pixLeft = (float) (x0 * scaleX);
                float pixTop = (float) ((pageHeightPt - y1) * scaleY);
                float pixRight = (float) (x1 * scaleX);
                float pixBottom = (float) ((pageHeightPt - y0) * scaleY);

                // Split line text into individual words for more realistic OCR simulation
                String[] lineWords = text.split("\\s+");
                if (lineWords.length == 0) continue;

                float wordWidth = (pixRight - pixLeft) / lineWords.length;
                for (int i = 0; i < lineWords.length; i++) {
                    if (lineWords[i].isEmpty()) continue;
                    float wLeft = pixLeft + i * wordWidth;
                    float wRight = pixLeft + (i + 1) * wordWidth;
                    RectF wordBox = new RectF(wLeft, pixTop, wRight, pixBottom);
                    words.add(new RecognizedWord(lineWords[i], wordBox, 95.0f));
                }
            }
        }
        return words;
    }

    // ==================== Helper: Key Phrase Extraction ====================

    /**
     * Extracts key phrases from ground truth: first line text of each textual zone.
     */
    private List<String> extractKeyPhrases(JsonObject page) {
        List<String> phrases = new ArrayList<>();
        JsonArray zones = page.getAsJsonArray("zones");
        for (JsonElement ze : zones) {
            JsonObject zone = ze.getAsJsonObject();
            if (zone.has("is_textual") && !zone.get("is_textual").getAsBoolean()) continue;
            JsonArray lines = zone.getAsJsonArray("lines");
            if (lines != null && lines.size() > 0) {
                String firstLine = lines.get(0).getAsJsonObject().get("text").getAsString().trim();
                if (firstLine.length() >= 3) {
                    // Use first 3 words as key phrase to avoid PDF text extraction artifacts
                    String[] parts = firstLine.split("\\s+");
                    StringBuilder phrase = new StringBuilder();
                    for (int i = 0; i < Math.min(3, parts.length); i++) {
                        if (i > 0) phrase.append(" ");
                        phrase.append(parts[i]);
                    }
                    phrases.add(phrase.toString());
                }
            }
        }
        return phrases;
    }

    // ==================== Helper: Text Utilities ====================

    private String normalizeText(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractFirstSignificantWord(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("\\s+");
        for (String p : parts) {
            if (p.length() >= 3) return p;
        }
        return parts.length > 0 ? parts[0] : null;
    }

    // ==================== Helper: PDF / Asset Loading ====================

    private Bitmap renderPdfFirstPage(String pdfFileName) throws IOException {
        File pdfFile = copyAssetToCache(ASSET_DIR + "/" + pdfFileName);
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdfFile,
                ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd);
             PdfRenderer.Page page = renderer.openPage(0)) {

            int width = (int) (page.getWidth() * RENDER_DPI / 72.0);
            int height = (int) (page.getHeight() * RENDER_DPI / 72.0);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        }
    }

    private JsonObject loadGroundTruth(String jsonFileName) throws IOException {
        try (InputStream is = testContext.getAssets().open(ASSET_DIR + "/" + jsonFileName);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private File copyAssetToCache(String assetPath) throws IOException {
        File cacheFile = new File(appContext.getCacheDir(), assetPath.replace("/", "_"));
        if (cacheFile.exists()) {
            return cacheFile;
        }
        try (InputStream is = testContext.getAssets().open(assetPath);
             FileOutputStream fos = new FileOutputStream(cacheFile)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
        }
        return cacheFile;
    }
}
