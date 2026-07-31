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
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
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
 * Spike-Eval: PaddleOCR-V5 Recognition mit alternativem Modell
 * ({@code latin_PP-OCRv5_mobile_rec}) gegen die Eval-Suite, ohne den
 * Produktivpfad oder die App-Assets anzufassen.
 *
 * <p>Voraussetzungen vor dem Lauf:
 * <ol>
 *   <li>{@code scripts/spike_paddle_rec_model.sh} (Modell-Download + ONNX-Export).
 *   <li>{@code scripts/spike_convert_latin_rec_to_ort.sh} (ONNX → ORT, basic-opt).
 *   <li>Push der Spike-Artefakte aufs Device:
 *       <pre>
 *       adb shell mkdir -p /sdcard/Android/data/de.schliweb.makeacopy/files/paddle-spike/latin
 *       adb push paddle-spike/onnx/latin_PP-OCRv5_mobile_rec/rec.ort \
 *           /sdcard/Android/data/de.schliweb.makeacopy/files/paddle-spike/latin/rec.ort
 *       adb push paddle-spike/onnx/latin_PP-OCRv5_mobile_rec/rec_dict.txt \
 *           /sdcard/Android/data/de.schliweb.makeacopy/files/paddle-spike/latin/rec_dict.txt
 *       </pre>
 * </ol>
 *
 * <p>Der Test aktiviert via Reflection den paket-privaten {@code
 * PaddleAssets.enableRecOverride(...)}-Hook, fährt {@link PaddleVsTesseractEvalTest}-kompatible
 * Metriken (CER/WER/meanConfidence/Latenzen) auf der Paddle-Engine und schreibt den Report nach
 * {@code Context.getExternalFilesDir("eval")/report-paddle-latin-<timestamp>.json}. Tesseract
 * wird nicht erneut gemessen — der bestehende Tesseract-Report aus {@link
 * PaddleVsTesseractEvalTest} ist die Vergleichsbasis.
 *
 * <p>Wenn die Spike-Artefakte fehlen, wird der Test mit {@link org.junit.Assume} übersprungen
 * statt fehlzuschlagen — er ist explizit ein Spike-Schalter, kein verpflichtender CI-Test.
 */
@RunWith(AndroidJUnit4.class)
public class LatinSpikeEvalTest {

    private static final String TAG = "LatinSpikeEval";
    private static final String EVAL_DIR = "eval";
    private static final String SPIKE_SUBDIR = "paddle-spike/latin";
    private static final String SPIKE_REC = "rec.ort";
    private static final String SPIKE_DICT = "rec_dict.txt";

    /** SHA256 aus dem Spike-Artefakt-Verzeichnis (paddle-spike/onnx/latin_PP-OCRv5_mobile_rec/). */
    private static final String SPIKE_DICT_SHA256 =
            "b95923300a0656f8169feee90143cbfcdb62d82a37b54e6b12c224c3e584916f";
    // rec.ort SHA wird im Konvertierungsschritt erst ermittelt (basic-opt
    // ist nicht-deterministisch zwischen ORT-Versionen); hier daher leer
    // lassen → der Override überspringt die SHA-Prüfung für rec.ort.
    private static final String SPIKE_REC_SHA256 = "";

    @Test
    public void evaluateLatinSpike_writesReport() throws Exception {
        // TODO(multi-model): Spike-Pfad benötigt eine neue, modelKey-aware Override-API.
        // Bis dahin wird dieser androidTest-Pfad bewusst übersprungen — die alte Single-Rec-
        // {@code PaddleAssets.enableRecOverride}-API wurde mit der Multi-Model-Migration entfernt
        // (siehe docs/paddleocr_v5_integration_concept.md §A).
        assumeTrue(
                "LatinSpikeEvalTest disabled after multi-model migration; rebuild with new API",
                false);
        assumeTrue(
                "arm64-v8a not present — skipping latin spike eval",
                Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"));

        Context ctx = ApplicationProvider.getApplicationContext();
        // Spike-Artefakte werden vom User vor dem Test ins externalFilesDir gepushed.
        File spikeDir = new File(ctx.getExternalFilesDir(null), SPIKE_SUBDIR);
        File spikeRec = new File(spikeDir, SPIKE_REC);
        File spikeDict = new File(spikeDir, SPIKE_DICT);
        assumeTrue(
                "Spike artifacts missing under " + spikeDir.getAbsolutePath()
                        + " — push rec.ort + rec_dict.txt and re-run.",
                spikeRec.isFile() && spikeDict.isFile());
        Log.i(TAG, "Spike artifacts: rec=" + spikeRec + " dict=" + spikeDict);

        // Override aktivieren via Reflection (paket-privat im paddle-Flavor).
        Class<?> assetsClass =
                Class.forName("de.schliweb.makeacopy.utils.ocr.paddle.PaddleAssets");
        Method enableM = assetsClass.getDeclaredMethod(
                "enableRecOverride", File.class, File.class, String.class, String.class);
        enableM.setAccessible(true);
        enableM.invoke(null, spikeRec, spikeDict, SPIKE_REC_SHA256, SPIKE_DICT_SHA256);
        Log.i(TAG, "Paddle rec-override enabled (latin spike).");

        // Debug-Dumps (Reflection wie im Hauptpfad).
        File debugOutDir = ctx.getExternalFilesDir("eval-debug-latin");
        Class<?> dumperClass = null;
        try {
            dumperClass = Class.forName("de.schliweb.makeacopy.utils.ocr.paddle.PaddleDebugDumper");
            Method enableDbg = dumperClass.getDeclaredMethod("enable", File.class, int.class);
            enableDbg.setAccessible(true);
            enableDbg.invoke(null, debugOutDir, 2);
            Log.i(TAG, "PaddleDebugDumper enabled, outDir=" + debugOutDir);
        } catch (Throwable t) {
            Log.w(TAG, "Could not enable PaddleDebugDumper (continuing without dumps): " + t);
            dumperClass = null;
        }

        try {
            Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();
            List<Sample> samples = loadSamples(testCtx);
            assumeTrue(
                    "no eval samples found under assets/" + EVAL_DIR + " — skipping",
                    !samples.isEmpty());
            Log.i(TAG, "Loaded " + samples.size() + " eval samples (latin spike)");

            OcrEngine paddle = PaddleEngineProvider.create(ctx, "eng");
            assertNotNull("Paddle engine must be creatable with override", paddle);
            Report report;
            try {
                report = runEngine("paddle-latin", paddle, samples);
            } finally {
                paddle.close();
            }

            File outDir = ctx.getExternalFilesDir(EVAL_DIR);
            if (outDir != null && (outDir.exists() || outDir.mkdirs())) {
                String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                        .format(LocalDateTime.now(ZoneId.systemDefault()));
                File f = new File(outDir, "report-paddle-latin-" + stamp + ".json");
                writeJson(f, report);
                Log.i(TAG, "Spike report written: " + f.getAbsolutePath());
            } else {
                Log.w(TAG, "External files dir not available, latin spike report not persisted");
            }
            Log.i(
                    TAG,
                    "[LATIN-SPIKE] cer=" + fmt(report.cer) + " wer=" + fmt(report.wer)
                            + " meanConf=" + fmt(report.meanConfidence)
                            + " p50=" + report.latencyMsP50 + "ms p95=" + report.latencyMsP95
                            + "ms n=" + report.nSamples);
        } finally {
            if (dumperClass != null) {
                try {
                    Method disableDbg = dumperClass.getDeclaredMethod("disable");
                    disableDbg.setAccessible(true);
                    disableDbg.invoke(null);
                } catch (Throwable t) {
                    Log.w(TAG, "Could not disable PaddleDebugDumper: " + t);
                }
            }
            try {
                Method disableM = assetsClass.getDeclaredMethod("disableRecOverride");
                disableM.setAccessible(true);
                disableM.invoke(null);
            } catch (Throwable t) {
                Log.w(TAG, "Could not disable rec-override: " + t);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Sample-Loader (kopiert aus PaddleVsTesseractEvalTest, paket-privat → bewusst dupliziert,
    // um den Hauptpfad nicht zu refaktorieren und Public-API stabil zu lassen).
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
            if (gt == null) continue;
            Bitmap bm;
            try (InputStream is = am.open(EVAL_DIR + "/" + img)) {
                bm = BitmapFactory.decodeStream(is);
            }
            if (bm == null) continue;
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
    // Eval-Loop (CER/WER aus PaddleVsTesseractEvalTest wiederverwendet).
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
            String gt = s.groundTruth == null ? "" : s.groundTruth.replace("\r\n", "\n").trim();
            String prN = pred.replace("\r\n", "\n").trim();
            double cer = PaddleVsTesseractEvalTest.cer(gt, prN);
            double wer = PaddleVsTesseractEvalTest.wer(gt, prN);
            cerSum += cer;
            werSum += wer;
            if (res != null && res.meanConfidence != null) {
                confSum += res.meanConfidence;
                confCount++;
            }
            r.perSample.add(new SampleMetric(s.name, cer, wer,
                    (res != null && res.meanConfidence != null) ? res.meanConfidence : -1, dtMs));
            Log.i(TAG, name + " sample=" + s.name + " cer=" + fmt(cer) + " wer=" + fmt(wer)
                    + " conf=" + (res != null ? res.meanConfidence : null) + " dtMs=" + dtMs);
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

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.4f", d);
    }

    // ---------------------------------------------------------------------
    // Report-DTO + minimaler JSON-Writer (parallel zu PaddleVsTesseractEvalTest).
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
        final double meanConfidence;
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
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
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
