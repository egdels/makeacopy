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

import static org.junit.Assert.assertNotNull;
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
