/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * End-to-End-Test für die Multi-Column-OCR (Issue #87): Die Beispiel-PDFs aus dem Issue werden
 * gerendert, mit Paddle erkannt und über {@link OCRPostProcessor#wordsToText(java.util.List,
 * boolean)} mit aktivierter Spaltenrekonstruktion zu Text zusammengesetzt. Geprüft wird die
 * Lesereihenfolge anhand markanter Wörter: Der komplette Inhalt der linken Spalte muss vor dem
 * Inhalt der rechten Spalte erscheinen, spaltenübergreifende Überschriften und Bereiche unter den
 * Spalten an der richtigen Stelle.
 */
@RunWith(AndroidJUnit4.class)
public class MultiColumnPdfPaddleTest {

    private static final String TAG = "MultiColumnPdfPaddle";
    private static final String ASSET_DIR = "multicolumn";

    /**
     * Synthetisches Zwei-Spalten-Beispiel aus dem Issue: links Lorem-Ipsum, rechts eine deutsche
     * Geschichte mit Zwischenüberschrift, darunter Full-Width-Zeilen (One/Two/…).
     */
    @Test
    public void ocrTestColumns_readingOrderFollowsColumns() throws Exception {
        String text = runMultiColumnOcr(ASSET_DIR + "/ocr_test_columns.pdf");
        Log.i(TAG, "ocr_test_columns text >>>\n" + text + "\n<<<");
        assertFalse("OCR text must not be empty", text.isEmpty());

        // Linke Spalte komplett vor der rechten Spalte: das Ende der linken Spalte
        // ("natoque penatibus", "Vestibulum ante ipsum") muss vor dem Anfang der rechten
        // Spalte ("hörte leise Schritte") liegen.
        assertOrder(text, "Lorem ipsum", "Integer sodales");
        assertOrder(text, "Integer sodales", "Aliquam velit");
        assertOrder(text, "natoque penatibus", "leise Schritte");
        // Rechte Spalte in sich korrekt: Geschichte -> Zwischenüberschrift -> Fortsetzung.
        assertOrder(text, "leise Schritte", "berschrift Dummy");
        assertOrder(text, "berschrift Dummy", "Gesetzeshüter");
        assertOrder(text, "Gesetzeshüter", "Fieberhaft");
        // Der separate "One/Two/…"-Block kommt zum Schluss.
        // ("Five five…" wird von der Detection nicht in jedem Lauf erkannt, daher "Fore".)
        assertOrder(text, "Fieberhaft", "One one one");
        assertOrder(text, "One one one", "Fore fore fore");
    }

    /**
     * Praxisbeispiel aus dem Issue (Zeitungsartikel): Überschrift über beide Spalten, Spalten
     * reichen nicht über die volle Seitenhöhe (Vorspann, Bild).
     */
    @Test
    public void newspaperArticle_readingOrderFollowsColumns() throws Exception {
        String text = runMultiColumnOcr(ASSET_DIR + "/newspaper_untergang.pdf");
        Log.i(TAG, "newspaper_untergang text >>>\n" + text + "\n<<<");
        assertFalse("OCR text must not be empty", text.isEmpty());

        // Überschrift vor dem Fließtext.
        assertOrder(text, "Untergang", "tragisches");
        // Linke Spalte komplett vor der rechten: "Rosemary" (Ende linke Spalte) muss vor
        // "Filmaufnahmen" (Anfang rechte Spalte) liegen.
        assertOrder(text, "Rosemary", "Filmaufnahmen");
        // Ende der rechten Spalte kommt zum Schluss.
        assertOrder(text, "Filmaufnahmen", "Sonntag");
    }

    /** Führt Render + Paddle-OCR + Multi-Column-Textaufbau für das angegebene PDF-Asset aus. */
    private static String runMultiColumnOcr(String assetPath) throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();
        Bitmap bitmap = renderPdfFirstPage(ctx, testCtx, assetPath);
        assertNotNull("PDF must render", bitmap);
        try (OcrEngine paddle = PaddleEngineProvider.create(ctx, "deu")) {
            assertNotNull("Paddle engine must be creatable", paddle);
            OCRHelper.OcrResultWords res = paddle.run(bitmap);
            assertNotNull(res);
            String singleFlow = OCRPostProcessor.wordsToText(res.words, false);
            String multiColumn = OCRPostProcessor.wordsToText(res.words, true);
            Log.i(TAG, assetPath + " words=" + res.words.size()
                    + " singleFlowLen=" + singleFlow.length()
                    + " multiColumnLen=" + multiColumn.length());
            return multiColumn;
        } finally {
            bitmap.recycle();
        }
    }

    /** Beide Marker müssen vorkommen und {@code first} vor {@code second} stehen. */
    private static void assertOrder(String text, String first, String second) {
        int i = text.indexOf(first);
        int j = text.indexOf(second);
        assertTrue("marker not found: \"" + first + "\"", i >= 0);
        assertTrue("marker not found: \"" + second + "\"", j >= 0);
        assertTrue(
                "wrong reading order: \"" + first + "\" (at " + i + ") must appear before \""
                        + second + "\" (at " + j + ")",
                i < j);
    }

    private static Bitmap renderPdfFirstPage(Context appCtx, Context testCtx, String assetPath)
            throws IOException {
        File pdfFile = copyAssetToCache(appCtx, testCtx, assetPath);
        try (ParcelFileDescriptor pfd =
                        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer renderer = new PdfRenderer(pfd)) {
            if (renderer.getPageCount() == 0) return null;
            PdfRenderer.Page page = renderer.openPage(0);
            try {
                // Parität zu PdfImportHelper.renderPdfPage: 300 DPI, 4096-Deckel.
                final int TARGET_DPI = 300;
                final float PDF_DPI = 72f;
                float scale = TARGET_DPI / PDF_DPI;
                int width = (int) (page.getWidth() * scale);
                int height = (int) (page.getHeight() * scale);
                final int MAX_DIMENSION = 4096;
                if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    float downScale =
                            Math.min((float) MAX_DIMENSION / width, (float) MAX_DIMENSION / height);
                    width = (int) (width * downScale);
                    height = (int) (height * downScale);
                    scale *= downScale;
                }
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(android.graphics.Color.WHITE);
                Matrix matrix = new Matrix();
                matrix.setScale(scale, scale);
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            } finally {
                page.close();
            }
        }
    }

    private static File copyAssetToCache(Context appCtx, Context testCtx, String assetPath)
            throws IOException {
        File outFile = new File(appCtx.getCacheDir(), new File(assetPath).getName());
        try (InputStream in = testCtx.getAssets().open(assetPath);
                FileOutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return outFile;
    }
}
