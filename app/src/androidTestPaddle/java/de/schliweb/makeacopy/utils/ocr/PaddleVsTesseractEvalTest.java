/*
 * Copyright 2025 Christian Kierdorf
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
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Eval-Test (Konzept §8 Punkt 8 + §9): Paddle vs. Tesseract auf einem Mini-Datensatz
 * unter {@code app/src/androidTestPaddle/assets/eval/}.
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
 * <p>Ergebnisse werden als JSON nach {@code Context.getExternalFilesDir("eval")/} geschrieben:
 * {@code report-paddle-<timestamp>.json}, {@code report-tesseract-<timestamp>.json}.
 *
 * <p>Akzeptanzschwellen aus §9 sind als Konstanten zentralisiert; das Test-Verhalten ist
 * <b>Soft-Assert</b>: bei Datensätzen kleiner Größe werden Schwellen nur geloggt, nicht hart
 * abgewiesen. Hartschalten in CI erfolgt in einem separaten Schritt mit größerem Datensatz.
 */
@RunWith(AndroidJUnit4.class)
public class PaddleVsTesseractEvalTest {

    private static final String TAG = "PaddleVsTesseractEval";
    private static final String EVAL_DIR = "eval";

    /** §9 Akzeptanzschwellen (Soft, vorläufig — Hartschalten mit größerem Datensatz). */
    static final double THRESHOLD_CER = 0.10;
    static final double THRESHOLD_WER = 0.20;
    static final double THRESHOLD_MEAN_CONFIDENCE = 60.0;

    @Test
    public void evaluatePaddleAndTesseract_writesReportsAndLogsThresholds() throws Exception {
        assumeTrue(
                "arm64-v8a not present — skipping Paddle vs. Tesseract eval",
                Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"));

        Context ctx = ApplicationProvider.getApplicationContext();
        // Eval-Assets liegen im Test-APK, nicht im App-APK → InstrumentationContext verwenden.
        Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();
        List<Sample> samples = loadSamples(testCtx);
        assumeTrue(
                "no eval samples found under assets/" + EVAL_DIR + " — skipping",
                !samples.isEmpty());
        Log.i(TAG, "Loaded " + samples.size() + " eval samples");

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

        // ----- Paddle -----
        OcrEngine paddle = PaddleEngineProvider.create(ctx, "eng");
        assertNotNull("Paddle engine must be creatable", paddle);
        Report paddleReport;
        try {
            paddleReport = runEngine("paddle", paddle, samples);
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

        // ----- Tesseract (über OCRHelper-Adapter) -----
        OCRHelper helper = new OCRHelper(ctx);
        helper.setLanguage("eng");
        boolean initialized = helper.initTesseract();
        assumeTrue("Tesseract could not initialize — skipping eval", initialized);
        Report tessReport;
        try {
            tessReport = runEngine("tesseract", new TesseractAdapter(helper), samples);
        } finally {
            helper.shutdown();
        }

        // Reports persistieren.
        File outDir = ctx.getExternalFilesDir(EVAL_DIR);
        if (outDir != null && (outDir.exists() || outDir.mkdirs())) {
            String stamp =
                    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                            .format(LocalDateTime.now(ZoneId.systemDefault()));
            writeJson(new File(outDir, "report-paddle-" + stamp + ".json"), paddleReport);
            writeJson(new File(outDir, "report-tesseract-" + stamp + ".json"), tessReport);
            Log.i(TAG, "Reports written to " + outDir.getAbsolutePath());
        } else {
            Log.w(TAG, "External files dir not available, reports not persisted");
        }

        // Soft-Asserts (§9): nur loggen.
        logThresholdCheck("paddle", paddleReport);
        logThresholdCheck("tesseract", tessReport);
    }

    // ---------------------------------------------------------------------
    // Sample-Loader
    // ---------------------------------------------------------------------

    private static final class Sample {
        final String name;
        final Bitmap bitmap;
        final String groundTruth;

        Sample(String name, Bitmap bitmap, String gt) {
            this.name = name;
            this.bitmap = bitmap;
            this.groundTruth = gt;
        }
    }

    private static List<Sample> loadSamples(Context ctx) throws Exception {
        AssetManager am = ctx.getAssets();
        String[] entries = am.list(EVAL_DIR);
        if (entries == null) return new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String e : entries) {
            String lower = e.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
                names.add(e);
            }
        }
        Collections.sort(names);

        List<Sample> out = new ArrayList<>(names.size());
        for (String img : names) {
            String base = img.replaceFirst("\\.[^.]+$", "");
            String gtName = base + ".gt.txt";
            String gt = readAssetText(am, EVAL_DIR + "/" + gtName);
            if (gt == null) {
                Log.w(TAG, "Skipping " + img + ": no GT file " + gtName);
                continue;
            }
            Bitmap bm;
            try (InputStream is = am.open(EVAL_DIR + "/" + img)) {
                bm = BitmapFactory.decodeStream(is);
            }
            if (bm == null) {
                Log.w(TAG, "Skipping " + img + ": decode failed");
                continue;
            }
            out.add(new Sample(base, bm, gt));
        }
        return out;
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
    // Engine-Adapter (Tesseract)
    // ---------------------------------------------------------------------

    private static final class TesseractAdapter implements OcrEngine {
        private final OCRHelper helper;

        TesseractAdapter(OCRHelper helper) {
            this.helper = helper;
        }

        @Override
        public String id() {
            return "tesseract";
        }

        @Override
        public boolean isAvailable(Context ctx) {
            return helper.isTesseractInitialized();
        }

        @Override
        public void setLanguage(String langSpec) {
            // Wird im Test bereits vor initTesseract gesetzt.
        }

        @Override
        public OCRHelper.OcrResultWords run(Bitmap bitmap) {
            return helper.runOcrWithRetry(bitmap);
        }

        @Override
        public void close() {
            // Lifecycle vom Test gesteuert.
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
                    name + " sample=" + s.name + " cer=" + fmt(cer) + " wer=" + fmt(wer)
                            + " conf=" + (res != null ? res.meanConfidence : null)
                            + " dtMs=" + dtMs);
        }

        Collections.sort(latencies);
        r.cer = cerSum / samples.size();
        r.wer = werSum / samples.size();
        r.meanConfidence = (confCount == 0) ? 0.0 : confSum / confCount;
        r.latencyMsP50 = percentile(latencies, 50);
        r.latencyMsP95 = percentile(latencies, 95);
        return r;
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

    private static void writeJson(File f, Report r) throws Exception {
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

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(bos.toByteArray());
        }
    }

    private static String jsonNum(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        return String.format(Locale.ROOT, "%.6f", d);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
