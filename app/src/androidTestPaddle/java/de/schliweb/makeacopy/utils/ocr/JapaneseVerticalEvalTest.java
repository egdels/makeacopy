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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Repro-Eval für Issue #88: vertikales Japanisch (Spalten oben→unten, rechts→links) verliert
 * Interpunktion (、 。) und kleine Kana (っ ッ ョ …) im Vergleich zum horizontalen Layout.
 *
 * <p>Assets: {@code eval/jpn/vertical_jpn.jpg} / {@code eval/jpn/horizontal_jpn.jpg} + Ground-Truth
 * (generiert via {@code scripts/generate_vertical_jpn_eval.py} aus dem Beispieltext des Issues).
 *
 * <p>Der Test loggt CER beider Layouts sowie pro kritischem Zeichen (、 。 っ ッ ョ) die
 * Vorkommen in Ground-Truth vs. Prediction. Soft-Assert-Stil wie die übrigen Eval-Tests:
 * Ergebnisse werden geloggt, die Reproduktion gilt als bestätigt, wenn die Vertical-CER
 * deutlich über der Horizontal-CER liegt und kritische Zeichen fehlen.
 */
@RunWith(AndroidJUnit4.class)
public class JapaneseVerticalEvalTest {

    private static final String TAG = "JapaneseVerticalEval";

    /** Kritische Zeichen aus dem Issue-Report (Interpunktion + kleine Kana). */
    private static final String[] CRITICAL_CHARS = {"、", "。", "っ", "ッ", "ョ"};

    @Test
    public void evaluateVerticalVsHorizontalJapanese() throws Exception {
        assumeTrue(
                "arm64-v8a not present — skipping Japanese vertical eval",
                Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"));

        Context ctx = ApplicationProvider.getApplicationContext();
        Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();

        Bitmap vertical = loadBitmap(testCtx, "eval/jpn/vertical_jpn.jpg");
        Bitmap horizontal = loadBitmap(testCtx, "eval/jpn/horizontal_jpn.jpg");
        String gt = loadText(testCtx, "eval/jpn/vertical_jpn.gt.txt");
        assumeTrue("eval assets missing — skipping", vertical != null && horizontal != null);

        OcrEngine engine = PaddleEngineProvider.create(ctx, "jpn");
        assertNotNull("Paddle engine must be creatable for jpn", engine);

        String predVertical;
        String predHorizontal;
        try {
            OCRHelper.OcrResultWords resV = engine.run(vertical);
            OCRHelper.OcrResultWords resH = engine.run(horizontal);
            predVertical = resV != null && resV.text != null ? resV.text : "";
            predHorizontal = resH != null && resH.text != null ? resH.text : "";
            Log.i(TAG, "vertical   conf=" + (resV != null ? resV.meanConfidence : null));
            Log.i(TAG, "horizontal conf=" + (resH != null ? resH.meanConfidence : null));
        } finally {
            engine.close();
        }

        Log.i(TAG, "GT:\n" + gt);
        Log.i(TAG, "PRED vertical:\n" + predVertical);
        Log.i(TAG, "PRED horizontal:\n" + predHorizontal);

        double cerV = cer(gt, predVertical);
        double cerH = cer(gt, predHorizontal);
        Log.i(
                TAG,
                String.format(
                        java.util.Locale.ROOT,
                        "CER vertical=%.4f horizontal=%.4f (issue #88 repro: vertical >> horizontal)",
                        cerV,
                        cerH));

        for (String ch : CRITICAL_CHARS) {
            int inGt = count(gt, ch);
            int inV = count(predVertical, ch);
            int inH = count(predHorizontal, ch);
            Log.i(
                    TAG,
                    "critical char '"
                            + ch
                            + "': gt="
                            + inGt
                            + " vertical="
                            + inV
                            + " horizontal="
                            + inH
                            + (inGt > 0 && inV < inGt ? "  << MISSING IN VERTICAL" : ""));
        }
    }

    /**
     * Realistische vertikale Bilder aus Issue #88 (Tester-Feedback zu 4.6.2-rc2):
     *
     * <ul>
     *   <li>{@code vertical_jpn_noto.png} — digital gerendert mit Noto Sans CJK JP und
     *       realistischem (engem) Buchsatz-Zeichenabstand.
     *   <li>{@code vertical_jpn_book.jpg} — Foto des echten Buchs (Serifenschrift,
     *       Kamerabeleuchtung, 478×3872 px Hochformat-Ausschnitt).
     * </ul>
     *
     * <p>Gleicher Referenztext wie das synthetische Bild → gleiche Ground-Truth. Der Test
     * loggt CER und die Statistik der kritischen Zeichen pro Bild getrennt. Asserts:
     * Engine liefert nicht-leeren Text pro Bild und die CER bleibt unter den
     * Regressionsschwellen (gemessen auf Pixel_9a-Emulator/arm64 nach der
     * Quad-Aufweitung + Foto-Enhance-Optimierung: noto 0.0247, Buchfoto 0.0864;
     * Schwellen mit Puffer für Geräte-Varianz).
     */
    @Test
    public void evaluateRealisticVerticalImages() throws Exception {
        assumeTrue(
                "arm64-v8a not present — skipping Japanese vertical eval",
                Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"));

        Context ctx = ApplicationProvider.getApplicationContext();
        Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();

        String gt = loadText(testCtx, "eval/jpn/vertical_jpn.gt.txt");
        String[] assets = {"eval/jpn/vertical_jpn_noto.png", "eval/jpn/vertical_jpn_book.jpg"};

        OcrEngine engine = PaddleEngineProvider.create(ctx, "jpn");
        assertNotNull("Paddle engine must be creatable for jpn", engine);
        try {
            for (String asset : assets) {
                Bitmap bmp = loadBitmap(testCtx, asset);
                assumeTrue("eval asset missing — skipping: " + asset, bmp != null);

                double maxCer = asset.endsWith(".jpg") ? 0.12 : 0.05;
                OCRHelper.OcrResultWords res = engine.run(bmp);
                String pred = res != null && res.text != null ? res.text : "";
                Log.i(TAG, "[" + asset + "] conf=" + (res != null ? res.meanConfidence : null));
                Log.i(TAG, "[" + asset + "] PRED:\n" + pred);
                Log.i(
                        TAG,
                        String.format(
                                java.util.Locale.ROOT,
                                "[%s] CER=%.4f (%dx%d px)",
                                asset,
                                cer(gt, pred),
                                bmp.getWidth(),
                                bmp.getHeight()));
                for (String ch : CRITICAL_CHARS) {
                    int inGt = count(gt, ch);
                    int inPred = count(pred, ch);
                    Log.i(
                            TAG,
                            "["
                                    + asset
                                    + "] critical char '"
                                    + ch
                                    + "': gt="
                                    + inGt
                                    + " pred="
                                    + inPred
                                    + (inGt > 0 && inPred < inGt ? "  << MISSING" : ""));
                }
                assertTrue("OCR must produce non-empty text for " + asset, !pred.isEmpty());
                assertTrue(
                        "CER regression for " + asset + ": " + cer(gt, pred) + " > " + maxCer,
                        cer(gt, pred) <= maxCer);
            }
        } finally {
            engine.close();
        }
    }

    /**
     * Guard gegen zu aggressive Normalisierung: Die CER-Metrik darf reale OCR-Fehler wie
     * fehlendes kleines っ (引っ越す → 引越す), fehlende Interpunktion (、 。) oder
     * Halbbreiten-Substitution (１ → 1) nicht verdecken. Nur Whitespace ist normalisiert.
     */
    @Test
    public void cerMetric_doesNotHideSmallKanaPunctuationOrWidthErrors() {
        assertTrue("small っ omission must count as error", cer("引っ越す", "引越す") > 0);
        assertTrue("missing 。 must count as error", cer("なくなった。", "なくなった") > 0);
        assertTrue("missing 、 must count as error", cer("すると、１億円", "すると１億円") > 0);
        assertTrue("small→large kana must count as error", cer("マンション", "マンシヨン") > 0);
        assertTrue("fullwidth １ vs 1 must count as error", cer("１億円", "1億円") > 0);
        // Nur layoutbedingter Whitespace (Zeilenumbrüche) darf neutralisiert werden.
        assertEquals(0.0, cer("引っ越す\nこと", "引っ越すこと"), 1e-9);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Bitmap loadBitmap(Context ctx, String assetPath) {
        try (InputStream is = ctx.getAssets().open(assetPath)) {
            return BitmapFactory.decodeStream(is);
        } catch (Exception e) {
            return null;
        }
    }

    private static String loadText(Context ctx, String assetPath) {
        try (BufferedReader r =
                new BufferedReader(
                        new InputStreamReader(
                                ctx.getAssets().open(assetPath), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int count(String s, String ch) {
        int n = 0;
        int idx = 0;
        while ((idx = s.indexOf(ch, idx)) >= 0) {
            n++;
            idx += ch.length();
        }
        return n;
    }

    /** CER über Codepoints, Whitespace-normalisiert (Zeilenumbrüche sind layoutabhängig). */
    static double cer(String gt, String pred) {
        int[] a = codepoints(stripWs(gt));
        int[] b = codepoints(stripWs(pred));
        if (a.length == 0) return b.length == 0 ? 0.0 : 1.0;
        return (double) levenshtein(a, b) / a.length;
    }

    private static String stripWs(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static int[] codepoints(String s) {
        return s.codePoints().toArray();
    }

    private static int levenshtein(int[] a, int[] b) {
        int[] prev = new int[b.length + 1];
        int[] curr = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) prev[j] = j;
        for (int i = 1; i <= a.length; i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length];
    }
}
