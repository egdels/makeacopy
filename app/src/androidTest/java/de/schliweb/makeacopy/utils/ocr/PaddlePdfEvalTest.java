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
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.schliweb.makeacopy.utils.image.ImageOps;
import de.schliweb.makeacopy.utils.geom.Point;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Eval-Test: Paddle auf PDF-Samples unter {@code app/src/androidTest/assets/eval/}.
 *
 * <p>Pro Engine werden die folgenden Metriken aggregiert:
 *
 * <ul>
 *   <li>{@code cer} — Character Error Rate (Levenshtein über Unicode-Codepoints, normalisiert auf
 *       GT-Länge).
 *   <li>{@code wer} — Word Error Rate (Wort-Levenshtein, Whitespace-Tokenisierung).
 *   <li>{@code meanConfidence} — Durchschnitt der {@code OcrResultWords.meanConfidence}.
 *   <li>{@code latencyMsP50/P95} — Pro-Sample-Latenz-Perzentile.
 * </ul>
 *
 * <p>Ergebnisse werden als JSON in die Logausgabe geschrieben.
 *
 * <p>Akzeptanzschwellen aus §9 sind als Konstanten zentralisiert; das Test-Verhalten ist
 * <b>Soft-Assert</b>: bei Datensätzen kleiner Größe werden Schwellen nur geloggt, nicht hart
 * abgewiesen. Hartschalten in CI erfolgt in einem separaten Schritt mit größerem Datensatz.
 */
@RunWith(AndroidJUnit4.class)
public class PaddlePdfEvalTest {

    private static final String TAG = "PaddlePdfEval";
    private static final String EVAL_DIR = "eval";

    /** §9 Akzeptanzschwellen (Soft, vorläufig — Hartschalten mit größerem Datensatz). */
    static final double THRESHOLD_CER = 0.10;
    static final double THRESHOLD_WER = 0.20;
    static final double THRESHOLD_MEAN_CONFIDENCE = 60.0;

    /**
     * Hart-Schwellen für das Regressionssample {@code saudi_executions}.
     * Dieses Sample deckt den DB-Detektor-Fix ab (zuvor fehlende Zeile
     * „In a bid to diversify its economy away from oil…"). Sollte die Detection-
     * Konfiguration künftig regredieren, wird die Zeile wieder verworfen — CER/WER
     * springen dann deutlich über diese Werte. Die Limits liegen großzügig oberhalb
     * der aktuell gemessenen Werte (CER≈0.059, WER≈0.070), um normale Recognizer-
     * Schwankungen zuzulassen, aber eine echte Detection-Regression hart zu fangen.
     */
    static final String HARD_REGRESSION_SAMPLE = "saudi_executions";
    static final double HARD_REGRESSION_CER = 0.08;
    static final double HARD_REGRESSION_WER = 0.12;

    @Test
    public void evaluatePaddlePdf_writesReportAndLogsThresholds() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        // Eval-Assets liegen im Test-APK, nicht im App-APK → InstrumentationContext verwenden.
        Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();
        List<Sample> samples = loadPdfSamples(ctx, testCtx);
        assumeTrue(
                "no PDF eval samples found under assets/" + EVAL_DIR + " — skipping",
                !samples.isEmpty());
        Log.i(TAG, "Loaded " + samples.size() + " PDF eval samples");

        // Debug-Dumps für die ersten 2 Paddle-Samples (Diagnose Det/Crop/Rec/CTC).
        // Erfolgt per Reflection, da PaddleDebugDumper paket-privat im paddle-Package liegt
        // und keine Public-API ergänzt werden soll.
        File debugOutDir = ctx.getExternalFilesDir("eval-debug");
        Class<?> dumperClass = null;
        try {
            dumperClass = Class.forName("de.schliweb.makeacopy.utils.ocr.paddle.PaddleDebugDumper");
            java.lang.reflect.Method enableM =
                    dumperClass.getDeclaredMethod("enable", File.class, int.class);
            enableM.setAccessible(true);
            enableM.invoke(null, debugOutDir, 2);
            Log.i(TAG, "PaddleDebugDumper enabled, outDir=" + debugOutDir);
        } catch (Throwable t) {
            Log.w(TAG, "Could not enable PaddleDebugDumper (continuing without dumps): " + t);
            dumperClass = null;
        }

        OcrEngine paddle = PaddleEngineProvider.create(ctx, null);
        assertNotNull("Paddle engine must be creatable", paddle);
        Report paddleReport;
        try {
            paddleReport = runEngine("paddle-pdf", paddle, samples);
        } finally {
            paddle.close();
            if (dumperClass != null) {
                try {
                    java.lang.reflect.Method disableM =
                            dumperClass.getDeclaredMethod("disable");
                    disableM.setAccessible(true);
                    disableM.invoke(null);
                } catch (Throwable t) {
                    Log.w(TAG, "Could not disable PaddleDebugDumper: " + t);
                }
            }
        }

        Log.i(TAG, "Report JSON:\n" + toJson(paddleReport));

        // Soft-Asserts (§9): nur loggen.
        logThresholdCheck("paddle-pdf", paddleReport);

        // Harter Per-Sample-Assert: Regression des DB-Detection-Fixes für saudi_executions.
        assertHardRegressionSample(paddleReport);
    }

    /**
     * Prüft das dedizierte Regressions-Sample hart gegen feste CER/WER-Schwellen.
     * Wenn das Sample nicht vorhanden ist (z.B. lokaler Lauf ohne Asset), wird der
     * Check übersprungen statt zu scheitern.
     */
    private static void assertHardRegressionSample(Report r) {
        SampleMetric sm = null;
        for (SampleMetric m : r.perSample) {
            if (HARD_REGRESSION_SAMPLE.equals(m.name)) {
                sm = m;
                break;
            }
        }
        if (sm == null) {
            Log.w(TAG, "[HARD-ASSERT] sample=" + HARD_REGRESSION_SAMPLE
                    + " not present — skipping regression guard");
            return;
        }
        Log.i(TAG, "[HARD-ASSERT] sample=" + sm.name
                + " cer=" + fmt(sm.cer) + " (limit " + HARD_REGRESSION_CER + ")"
                + " wer=" + fmt(sm.wer) + " (limit " + HARD_REGRESSION_WER + ")");
        assertTrue(
                "Regression in sample " + sm.name + ": CER=" + fmt(sm.cer)
                        + " exceeds hard limit " + HARD_REGRESSION_CER
                        + " — DB detection likely regressed (missing text lines).",
                sm.cer <= HARD_REGRESSION_CER);
        assertTrue(
                "Regression in sample " + sm.name + ": WER=" + fmt(sm.wer)
                        + " exceeds hard limit " + HARD_REGRESSION_WER
                        + " — DB detection likely regressed (missing text lines).",
                sm.wer <= HARD_REGRESSION_WER);
    }

    // ---------------------------------------------------------------------
    // Sample-Loader
    // ---------------------------------------------------------------------

    private static final class Sample {
        final String name;
        final String language;
        final Bitmap bitmap;
        final String groundTruth;

        Sample(String name, String language, Bitmap bitmap, String gt) {
            this.name = name;
            this.language = language;
            this.bitmap = bitmap;
            this.groundTruth = gt;
        }
    }

    private static List<Sample> loadPdfSamples(Context appCtx, Context testCtx) throws Exception {
        AssetManager am = testCtx.getAssets();
        String[] entries = am.list(EVAL_DIR);
        if (entries == null) return new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String e : entries) {
            String lower = e.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf")) {
                names.add(e);
            }
        }
        Collections.sort(names);

        List<Sample> out = new ArrayList<>(names.size());
        for (String pdf : names) {
            String base = pdf.replaceFirst("\\.[^.]+$", "");
            String gtName = base + ".gt.txt";
            String gt = readAssetText(am, EVAL_DIR + "/" + gtName);
            if (gt == null) {
                Log.w(TAG, "Skipping " + pdf + ": no GT file " + gtName);
                continue;
            }
            Bitmap bm = renderPdfFirstPage(appCtx, testCtx, EVAL_DIR + "/" + pdf);
            if (bm == null) {
                Log.w(TAG, "Skipping " + pdf + ": render failed");
                continue;
            }
            // App-Pipeline-Parität: nach dem PDF-Render durchläuft das Bitmap in der App
            // CropFragment.performCrop, selbst wenn der User „volles Bild" wählt. Dieser
            // Warp-Schritt reproduziert das Resampling, das im Test bislang fehlte.
            Bitmap cropped = fullImageCropLikeApp(appCtx, base, bm);
            if (cropped != bm) {
                bm.recycle();
            }
            bm = cropped;
            out.add(new Sample(base, languageFromSampleName(base), bm, gt));
        }
        return out;
    }

    private static String languageFromSampleName(String name) {
        String prefix = "language_";
        if (name != null && name.startsWith(prefix) && name.length() > prefix.length()) {
            return name.substring(prefix.length());
        }
        return "eng";
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
                // Bit-für-Bit-Nachbildung von PdfImportHelper.renderPdfPage:
                //   TARGET_DPI=300, PDF_DPI=72, scale=300/72, Matrix.setScale,
                //   weißer Hintergrund, MAX_DIMENSION=4096-Deckel,
                //   RENDER_MODE_FOR_DISPLAY.
                // Wichtig: page.render(..., matrix, ...) statt null, damit dieselbe
                // Sampling-Pipeline wie im App-Pfad zum Einsatz kommt.
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

                boolean forceSaudiAppWidth = assetPath.endsWith("/saudi_executions.pdf");
                float scaleX = scale;
                float scaleY = scale;
                if (forceSaudiAppWidth && width != 2479 && height == 3504) {
                    Log.w(
                            TAG,
                            "renderPdfFirstPage: forcing saudi_executions width from " + width
                                    + " to 2479 to match app crop source log");
                    width = 2479;
                    scaleX = (float) width / page.getWidth();
                }

                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(android.graphics.Color.WHITE);

                Matrix matrix = new Matrix();
                matrix.setScale(scaleX, scaleY);

                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            } finally {
                page.close();
            }
        }
    }

    /**
     * Reproduziert den App-Pfad {@code CropFragment.performCrop}: aspect=AUTO →
     * {@code WarpMode.AUTO_PROJECTIVE}. Für {@code saudi_executions} werden die im
     * App-Log beobachteten echten Crop-Ecken verwendet (2479×3504 → 2455×3505),
     * für andere Samples bleibt es beim bisherigen Vollbild-Trapez.
     */
    private static Bitmap fullImageCropLikeApp(Context ctx, String sampleName, Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean useSaudiAppCorners = "saudi_executions".equals(sampleName);
        Point[] corners = useSaudiAppCorners
                ? new Point[] {
                        new Point(24.79, 0.0),
                        new Point(2479, 0.0),
                        new Point(2479, 3504),
                        new Point(24.79, 3504)
                }
                : new Point[] {
                        new Point(0, 0),
                        new Point(w, 0),
                        new Point(w, h),
                        new Point(0, h)
                };
        Log.i(
                TAG,
                "fullImageCropLikeApp: sample=" + sampleName
                        + " src=" + w + "x" + h
                        + " corners=" + formatCorners(corners));
        if (useSaudiAppCorners && (w != 2479 || h != 3504)) {
            Log.w(
                    TAG,
                    "fullImageCropLikeApp: saudi_executions render size differs from app log: "
                            + w + "x" + h + " (expected 2479x3504)");
        }
        Bitmap cropped =
                ImageOps.applyPerspectiveCorrection(
                        src, corners, ImageOps.WarpMode.AUTO_PROJECTIVE, null);
        if (cropped == null) {
            Log.w(TAG, "fullImageCropLikeApp: crop returned null — falling back to source");
            return src;
        }
        Log.i(
                TAG,
                "fullImageCropLikeApp: sample=" + sampleName + " src=" + w + "x" + h
                        + " -> cropped=" + cropped.getWidth() + "x" + cropped.getHeight());
        return cropped;
    }

    private static String formatCorners(Point[] corners) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < corners.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('(')
                    .append(corners[i].x)
                    .append(',')
                    .append(corners[i].y)
                    .append(')');
        }
        return sb.append(']').toString();
    }

    private static File copyAssetToCache(Context appCtx, Context testCtx, String assetPath)
            throws IOException {
        File outFile = new File(appCtx.getCacheDir(), new File(assetPath).getName());
        if (outFile.exists() && outFile.length() > 0) {
            return outFile;
        }
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

    private static String readAssetText(AssetManager am, String path) {
        try (InputStream is = am.open(path);
                BufferedReader br =
                        new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (!first) sb.append('\n');
                sb.append(line);
                first = false;
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }


    // ---------------------------------------------------------------------
    // Eval-Loop
    // ---------------------------------------------------------------------

    private static Report runEngine(String name, OcrEngine engine, List<Sample> samples)
            throws Exception {
        Report r = new Report(name, samples.size());
        List<Long> latencies = new ArrayList<>(samples.size());
        double cerSum = 0.0;
        double werSum = 0.0;
        double confSum = 0.0;
        int confCount = 0;

        for (Sample s : samples) {
            engine.setLanguage(s.language);
            logBitmapInfo(name + " sample=" + s.name + " before engine.run", s.bitmap);
            long t0 = System.nanoTime();
            OCRHelper.OcrResultWords res = engine.run(s.bitmap);
            long dtMs = (System.nanoTime() - t0) / 1_000_000L;
            latencies.add(dtMs);
            String pred = (res != null && res.text != null) ? res.text : "";
            String gt = normalizeText(s.groundTruth);
            String prN = normalizeText(pred);
            double cer = cer(gt, prN);
            double wer = wer(gt, prN);
            cerSum += cer;
            werSum += wer;
            if (res != null && res.meanConfidence != null) {
                confSum += res.meanConfidence;
                confCount++;
            }
            r.perSample.add(
                    new SampleMetric(s.name, cer, wer,
                            (res != null && res.meanConfidence != null) ? res.meanConfidence : -1,
                            dtMs));
            Log.i(
                    TAG,
                    name + " sample=" + s.name + " lang=" + s.language + " cer=" + fmt(cer) + " wer=" + fmt(wer)
                            + " conf=" + (res != null ? res.meanConfidence : null)
                            + " dtMs=" + dtMs);
            // Erkannten Text vollständig in die Logausgabe schreiben (Diagnose / Vergleich mit GT).
            // Logcat begrenzt einzelne Einträge auf ~4 KB; daher zeilenweise loggen.
            logMultiline(TAG, name + " sample=" + s.name + " PRED >>>", prN);
            logMultiline(TAG, name + " sample=" + s.name + " PRED <<< END", "");
            logMultiline(TAG, name + " sample=" + s.name + " GT   >>>", gt);
            logMultiline(TAG, name + " sample=" + s.name + " GT   <<< END", "");
        }

        Collections.sort(latencies);
        r.cer = cerSum / samples.size();
        r.wer = werSum / samples.size();
        r.meanConfidence = (confCount == 0) ? 0.0 : confSum / confCount;
        r.latencyMsP50 = percentile(latencies, 50);
        r.latencyMsP95 = percentile(latencies, 95);
        return r;
    }

    private static void logBitmapInfo(String prefix, Bitmap bitmap) {
        if (bitmap == null) {
            Log.i(TAG, "Bitmap config: " + prefix + " bitmap=null");
            return;
        }
        Log.i(
                TAG,
                "Bitmap config: " + prefix
                        + " size=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                        + " config=" + bitmap.getConfig()
                        + " premultiplied=" + bitmap.isPremultiplied()
                        + " hasAlpha=" + bitmap.hasAlpha()
                        + " colorSpace=" + bitmap.getColorSpace()
                        + " allocationByteCount=" + bitmap.getAllocationByteCount()
                        + " rowBytes=" + bitmap.getRowBytes());
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0L;
        int idx = (int) Math.ceil((p / 100.0) * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    private static void logThresholdCheck(String engine, Report r) {
        boolean cerOk = r.cer <= THRESHOLD_CER;
        boolean werOk = r.wer <= THRESHOLD_WER;
        boolean confOk = r.meanConfidence >= THRESHOLD_MEAN_CONFIDENCE;
        Log.i(
                TAG,
                "[§9 SOFT-ASSERT] engine=" + engine
                        + " cer=" + fmt(r.cer) + (cerOk ? " (OK)" : " (>" + THRESHOLD_CER + ")")
                        + " wer=" + fmt(r.wer) + (werOk ? " (OK)" : " (>" + THRESHOLD_WER + ")")
                        + " meanConf=" + fmt(r.meanConfidence)
                        + (confOk ? " (OK)" : " (<" + THRESHOLD_MEAN_CONFIDENCE + ")")
                        + " p50=" + r.latencyMsP50 + "ms p95=" + r.latencyMsP95 + "ms n="
                        + r.nSamples);
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.4f", d);
    }

    /**
     * Logge potenziell mehrzeiligen Text Zeile für Zeile, damit Logcat nichts abschneidet.
     * Erste Zeile bekommt das Header-Präfix, Folgezeilen werden mit Zeilennummer markiert.
     */
    private static void logMultiline(String tag, String header, String body) {
        if (body == null || body.isEmpty()) {
            Log.i(tag, header);
            return;
        }
        String[] lines = body.split("\n", -1);
        Log.i(tag, header + " (" + lines.length + " lines)");
        for (int i = 0; i < lines.length; i++) {
            Log.i(tag, "  [" + (i + 1) + "] " + lines[i]);
        }
    }

    private static String normalizeText(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    // ---------------------------------------------------------------------
    // CER/WER (codepoint-aware) — selbsthaltend, identische Implementierung wie
    // {@code OcrEvalRunner} in {@code testPaddle}, dort durch JVM-Tests gehärtet.
    // ---------------------------------------------------------------------

    static double cer(String gt, String pred) {
        if (gt == null) gt = "";
        if (pred == null) pred = "";
        int[] gtCp = toCodepoints(gt);
        int[] prCp = toCodepoints(pred);
        if (gtCp.length == 0) return prCp.length == 0 ? 0.0 : 1.0;
        return (double) levenshteinInts(gtCp, prCp) / gtCp.length;
    }

    static double wer(String gt, String pred) {
        if (gt == null) gt = "";
        if (pred == null) pred = "";
        String[] gtTok = tokenize(gt);
        String[] prTok = tokenize(pred);
        if (gtTok.length == 0) return prTok.length == 0 ? 0.0 : 1.0;
        return (double) levenshteinTokens(gtTok, prTok) / gtTok.length;
    }

    private static int[] toCodepoints(String s) {
        if (s.isEmpty()) return new int[0];
        int[] cps = new int[s.codePointCount(0, s.length())];
        int idx = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            cps[idx++] = cp;
            i += Character.charCount(cp);
        }
        return cps;
    }

    private static String[] tokenize(String s) {
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return new String[0];
        return trimmed.split("\\p{IsWhite_Space}+");
    }

    private static int levenshteinInts(int[] a, int[] b) {
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

    private static int levenshteinTokens(String[] a, String[] b) {
        int[] prev = new int[b.length + 1];
        int[] curr = new int[b.length + 1];
        for (int j = 0; j <= b.length; j++) prev[j] = j;
        for (int i = 1; i <= a.length; i++) {
            curr[0] = i;
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1].equals(b[j - 1]) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length];
    }

    // ---------------------------------------------------------------------
    // Report-DTO + minimaler JSON-Writer (kein zusätzlicher Dep für Tests)
    // ---------------------------------------------------------------------

    private static final class Report {
        final String engine;
        final int nSamples;
        final List<SampleMetric> perSample = new ArrayList<>();
        double cer;
        double wer;
        double meanConfidence;
        long latencyMsP50;
        long latencyMsP95;

        Report(String engine, int nSamples) {
            this.engine = engine;
            this.nSamples = nSamples;
        }
    }

    private static final class SampleMetric {
        final String name;
        final double cer;
        final double wer;
        final double meanConfidence; // -1 wenn unbekannt
        final long latencyMs;

        SampleMetric(String name, double cer, double wer, double meanConfidence, long latencyMs) {
            this.name = name;
            this.cer = cer;
            this.wer = wer;
            this.meanConfidence = meanConfidence;
            this.latencyMs = latencyMs;
        }
    }

    private static String toJson(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"engine\": \"").append(escape(r.engine)).append("\",\n");
        sb.append("  \"nSamples\": ").append(r.nSamples).append(",\n");
        sb.append("  \"cer\": ").append(jsonNum(r.cer)).append(",\n");
        sb.append("  \"wer\": ").append(jsonNum(r.wer)).append(",\n");
        sb.append("  \"meanConfidence\": ").append(jsonNum(r.meanConfidence)).append(",\n");
        sb.append("  \"latencyMsP50\": ").append(r.latencyMsP50).append(",\n");
        sb.append("  \"latencyMsP95\": ").append(r.latencyMsP95).append(",\n");
        sb.append("  \"thresholds\": {\n");
        sb.append("    \"cer\": ").append(jsonNum(THRESHOLD_CER)).append(",\n");
        sb.append("    \"wer\": ").append(jsonNum(THRESHOLD_WER)).append(",\n");
        sb.append("    \"meanConfidence\": ").append(jsonNum(THRESHOLD_MEAN_CONFIDENCE)).append("\n");
        sb.append("  },\n");
        sb.append("  \"perSample\": [\n");
        for (int i = 0; i < r.perSample.size(); i++) {
            SampleMetric sm = r.perSample.get(i);
            sb.append("    {\"name\": \"").append(escape(sm.name)).append("\"")
                    .append(", \"cer\": ").append(jsonNum(sm.cer))
                    .append(", \"wer\": ").append(jsonNum(sm.wer))
                    .append(", \"meanConfidence\": ").append(jsonNum(sm.meanConfidence))
                    .append(", \"latencyMs\": ").append(sm.latencyMs)
                    .append("}");
            if (i < r.perSample.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String jsonNum(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        return String.format(Locale.ROOT, "%.6f", d);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
