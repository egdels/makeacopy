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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility class for debugging PaddleOCR-based processes through persistence of intermediate
 * data such as recognition crops, JSON metadata, and augmented input images.
 *
 * The class operates as a static utility and maintains its state internally, allowing it to:
 * - Enable or disable debugging based on runtime requirements.
 * - Register samples and manage file-based outputs for captured bitmaps.
 * - Persist metadata and images for diagnostics and performance evaluation.
 */
final class PaddleDebugDumper {

    private static final String TAG = "PaddleDebugDumper";

    /**
     * The maximum number of head frames used for Argmax processing within the PaddleDebugDumper.
     *
     * <p>This variable defines the upper limit on the number of Argmax head outputs that can be
     * processed or stored during recognition dumps. It serves as a configuration parameter to
     * constrain computational or storage resources while managing detection or recognition outputs.
     */
    static final int ARGMAX_HEAD_FRAMES = 32;

    private static volatile boolean enabled = false;
    private static volatile File outRoot = null;
    private static volatile int maxSamples = 0;

    private static final AtomicInteger registeredSamples = new AtomicInteger(0);
    private static final Map<Bitmap, SampleState> states = new WeakHashMap<>();

    private PaddleDebugDumper() {}

    /**
     * Enables the PaddleDebugDumper by configuring the output directory and maximum sample limit.
     * If the output directory is null, the method disables the dumper instead. If the directory
     * does not exist and cannot be created, the dumper will also be disabled.
     *
     * @param outRootDir The root directory where debug data will be stored. If null, the dumper is disabled.
     * @param maxSamplesLimit The maximum number of samples to retain. Must be a non-negative integer.
     */
    static synchronized void enable(File outRootDir, int maxSamplesLimit) {
        if (outRootDir == null) {
            disable();
            return;
        }
        if (!outRootDir.exists() && !outRootDir.mkdirs()) {
            Log.w(TAG, "Could not create out dir: " + outRootDir);
            disable();
            return;
        }
        outRoot = outRootDir;
        maxSamples = Math.max(0, maxSamplesLimit);
        enabled = true;
        registeredSamples.set(0);
        synchronized (states) {
            states.clear();
        }
        Log.i(TAG, "enabled outRoot=" + outRootDir.getAbsolutePath() + " maxSamples=" + maxSamples);
    }

    static synchronized void disable() {
        enabled = false;
        outRoot = null;
        maxSamples = 0;
        synchronized (states) {
            states.clear();
        }
    }

    static boolean isEnabled() {
        return enabled;
    }

    /**
     * Registers a new sample for debugging purposes. A sample ID is generated, and a corresponding
     * directory is created on the file system to store related debug information.
     * The sample is added to the internal state if the maximum sample limit has not been reached and
     * the provided bitmap is valid.
     *
     * @param full The bitmap representing the full image to register. Must not be null or recycled.
     * @param hint A string hint to incorporate into the generated sample ID. If null or empty,
     *             defaults to a sanitized version of "sample".
     * @return The generated sample ID, or null if registration failed (e.g., invalid input,
     *         dumper is disabled, or maximum sample limit has been reached).
     */
    static String registerSample(Bitmap full, String hint) {
        if (!enabled || full == null || full.isRecycled()) return null;
        synchronized (states) {
            SampleState existing = states.get(full);
            if (existing != null) return existing.sampleId;
            int idx = registeredSamples.get();
            if (idx >= maxSamples) return null;
            registeredSamples.incrementAndGet();
            String safeHint = (hint == null || hint.isEmpty()) ? "sample" : sanitize(hint);
            String sampleId = String.format(Locale.ROOT, "%02d_%s", idx, safeHint);
            File dir = new File(outRoot, sampleId);
            if (!dir.exists()) dir.mkdirs();
            SampleState s = new SampleState(sampleId, dir);
            states.put(full, s);
            Log.i(TAG, "registered sample id=" + sampleId + " dir=" + dir.getAbsolutePath());
            return sampleId;
        }
    }

    /**
     * Dumps a copy of the original bitmap overlaid with visual representations of the given quads.
     * Each quad is rendered as a closed polygon with stroke and numbered label in red.
     * The processed image is saved as a PNG file in the corresponding sample directory.
     * This method is intended for debugging purposes.
     *
     * @param full The original bitmap to be overlaid with quads. Must not be null, recycled,
     *             or unregistered when the method is called.
     * @param quads A list of quads to draw on the bitmap. Each quad represents a rotated text region
     *              with vertices in top-left, top-right, bottom-right, bottom-left order.
     *              Passing null or an empty list results in no operation.
     */
    static void dumpOriginalWithQuads(Bitmap full, List<Quad> quads) {
        if (!enabled || full == null || full.isRecycled() || quads == null) return;
        SampleState s;
        synchronized (states) {
            s = states.get(full);
        }
        if (s == null) return;
        try {
            Bitmap copy = full.copy(Bitmap.Config.ARGB_8888, true);
            try {
                Canvas c = new Canvas(copy);
                Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(Math.max(2f, copy.getWidth() / 600f));
                stroke.setColor(Color.RED);
                Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
                label.setColor(Color.RED);
                label.setTextSize(Math.max(18f, copy.getWidth() / 90f));
                for (int i = 0; i < quads.size(); i++) {
                    Quad q = quads.get(i);
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo((float) q.x[0], (float) q.y[0]);
                    p.lineTo((float) q.x[1], (float) q.y[1]);
                    p.lineTo((float) q.x[2], (float) q.y[2]);
                    p.lineTo((float) q.x[3], (float) q.y[3]);
                    p.close();
                    c.drawPath(p, stroke);
                    c.drawText(
                            String.format(Locale.ROOT, "%d", i),
                            (float) q.x[0],
                            Math.max((float) q.y[0] - 4f, label.getTextSize()),
                            label);
                }
                File f = new File(s.dir, "original_with_quads.png");
                writePng(copy, f);
                synchronized (s) {
                    s.quadCount = quads.size();
                }
            } finally {
                if (!copy.isRecycled()) copy.recycle();
            }
        } catch (Throwable t) {
            Log.w(TAG, "dumpOriginalWithQuads failed", t);
        }
    }

    /**
     * Dumps crop-related debug information for the specified sample. This method saves the
     * crop image, updates the sample's JSON metadata with crop details, and optionally appends
     * recognition outputs if provided. This function is used to record debugging data for a
     * specific crop region within an image.
     *
     * @param full The full bitmap image associated with the sample. Must be a valid, registered
     *             non-null, and unrecycled bitmap.
     * @param index The index representing the position or sequence number of the crop
     *              within the current sample.
     * @param q The quadrilateral defining the rotated bounding box (text region) for the crop
     *          in image coordinates. The quad must have vertices specified in top-left,
     *          top-right, bottom-right, bottom-left order.
     * @param crop A bitmap representing the cropped image extracted from the full image. Pass
     *             null if no cropped bitmap is available or applicable.
     * @param out The recognition output, which may include text, confidence score, crop width,
     *            scaled crop width, frame count, and argmax ID head information. If null, some
     *            of these fields are omitted in the JSON metadata.
     */
    static void dumpCrop(
            Bitmap full,
            int index,
            Quad q,
            Bitmap crop,
            PaddleRecOrtRunner.RecOutput out) {
        if (!enabled || full == null) return;
        SampleState s;
        synchronized (states) {
            s = states.get(full);
        }
        if (s == null) return;
        try {
            String name = String.format(Locale.ROOT, "crop_%03d.png", index);
            if (crop != null && !crop.isRecycled()) {
                writePng(crop, new File(s.dir, name));
            }
            int cw = crop != null ? crop.getWidth() : 0;
            int ch = crop != null ? crop.getHeight() : 0;
            String text = (out != null && out.text() != null) ? out.text() : "";
            float conf = out != null ? out.confidence() : 0f;
            int paddedW = out != null ? out.paddedCropWidth() : 0;
            int scaledW = out != null ? out.scaledCropWidth() : 0;
            int frameCount = out != null ? out.frameCount() : 0;
            int[] argmax = out != null ? out.argmaxIdsHead() : null;
            synchronized (s) {
                if (!s.firstCrop) s.json.append(",\n");
                s.firstCrop = false;
                s.json.append("    {\n");
                s.json.append("      \"index\": ").append(index).append(",\n");
                s.json.append("      \"file\": \"").append(name).append("\",\n");
                s.json.append("      \"quad\": {")
                        .append("\"x\": [")
                        .append(fmt(q.x[0]))
                        .append(",")
                        .append(fmt(q.x[1]))
                        .append(",")
                        .append(fmt(q.x[2]))
                        .append(",")
                        .append(fmt(q.x[3]))
                        .append("], \"y\": [")
                        .append(fmt(q.y[0]))
                        .append(",")
                        .append(fmt(q.y[1]))
                        .append(",")
                        .append(fmt(q.y[2]))
                        .append(",")
                        .append(fmt(q.y[3]))
                        .append("]},\n");
                s.json.append("      \"cropSize\": [")
                        .append(cw)
                        .append(",")
                        .append(ch)
                        .append("],\n");
                s.json.append("      \"recInputShape\": [1, 3, 48, ")
                        .append(paddedW)
                        .append("],\n");
                s.json.append("      \"scaledCropWidth\": ")
                        .append(scaledW)
                        .append(",\n");
                s.json.append("      \"frameCount\": ")
                        .append(frameCount)
                        .append(",\n");
                s.json.append("      \"confidence\": ")
                        .append(fmt(conf))
                        .append(",\n");
                s.json.append("      \"text\": \"")
                        .append(escape(text))
                        .append("\",\n");
                s.json.append("      \"argmaxIdsHead\": [");
                if (argmax != null) {
                    for (int i = 0; i < argmax.length; i++) {
                        if (i > 0) s.json.append(",");
                        s.json.append(argmax[i]);
                    }
                }
                s.json.append("]\n    }");
            }
        } catch (Throwable t) {
            Log.w(TAG, "dumpCrop failed idx=" + index, t);
        }
    }

    /**
     * Finalizes a sample by generating a JSON metadata file containing details about the sample,
     * including its ID, quad count, crops data, and aggregate recognition results. If the sample
     * exists in the internal state, it is removed after processing. The method writes the generated
     * data to a file named "sample.json" in the sample's directory.
     *
     * @param full The bitmap representing the full image associated with the sample. Must not be null.
     *             If the bitmap is null or the dumper is disabled, the method exits without performing
     *             any operation.
     * @param aggregateText The aggregate text recognition result for the sample. This value may be null,
     *                      in which case an empty string is used in the resulting JSON metadata.
     * @param aggregateMeanConf The aggregate mean confidence score for the text recognition result. This
     *                          may be null, in which case the value "null" will be used in the JSON metadata.
     */
    static void finishSample(Bitmap full, String aggregateText, Integer aggregateMeanConf) {
        if (!enabled || full == null) return;
        SampleState s;
        synchronized (states) {
            s = states.remove(full);
        }
        if (s == null) return;
        try {
            StringBuilder full_ = new StringBuilder();
            full_.append("{\n");
            full_.append("  \"sampleId\": \"")
                    .append(escape(s.sampleId))
                    .append("\",\n");
            full_.append("  \"quadCount\": ")
                    .append(s.quadCount)
                    .append(",\n");
            full_.append("  \"crops\": [\n");
            full_.append(s.json);
            full_.append("\n  ],\n");
            full_.append("  \"aggregateText\": \"")
                    .append(escape(aggregateText == null ? "" : aggregateText))
                    .append("\",\n");
            full_.append("  \"aggregateMeanConfidence\": ")
                    .append(aggregateMeanConf == null ? "null" : aggregateMeanConf.toString())
                    .append("\n");
            full_.append("}\n");
            File f = new File(s.dir, "sample.json");
            try (BufferedWriter w =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    new FileOutputStream(f), StandardCharsets.UTF_8))) {
                w.write(full_.toString());
            }
            Log.i(TAG, "finished sample " + s.sampleId + " → " + f.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "finishSample failed", t);
        }
    }

    private static void writePng(Bitmap bm, File out) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
    }

    private static String fmt(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        return String.format(Locale.ROOT, "%.3f", d);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length() && sb.length() < 40; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') sb.append(c);
            else sb.append('_');
        }
        return sb.length() == 0 ? "sample" : sb.toString();
    }

    private static final class SampleState {
        final String sampleId;
        final File dir;
        final StringBuilder json = new StringBuilder();
        boolean firstCrop = true;
        int quadCount = -1;

        SampleState(String sampleId, File dir) {
            this.sampleId = sampleId;
            this.dir = dir;
        }
    }
}
