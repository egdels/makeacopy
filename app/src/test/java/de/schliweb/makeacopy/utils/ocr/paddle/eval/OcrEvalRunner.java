/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr.paddle.eval;

import de.schliweb.makeacopy.utils.ocr.OCRHelper;
import de.schliweb.makeacopy.utils.ocr.OcrEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Eval-Runner-Skelett (§9 Konzept).
 *
 * <p>Iteriert über {@link OcrEvalSample}s, ruft {@link OcrEngine#run(android.graphics.Bitmap)}
 * auf und aggregiert CER/WER/Confidence/Latency-Perzentile in einen {@link OcrEvalReport}.
 *
 * <p>Diese Session liefert lediglich das Wiring; ein echter Eval-Lauf gegen einen Datensatz folgt
 * in einer separaten Session.
 */
public final class OcrEvalRunner {

    public OcrEvalReport run(List<OcrEvalSample> samples, OcrEngine engine) throws Exception {
        if (samples == null || samples.isEmpty()) {
            return new OcrEvalReport(0.0, 0.0, 0.0, 0L, 0L, 0);
        }
        double cerSum = 0.0;
        double werSum = 0.0;
        double confSum = 0.0;
        int confCount = 0;
        List<Long> latencies = new ArrayList<>(samples.size());

        for (OcrEvalSample s : samples) {
            long t0 = System.nanoTime();
            OCRHelper.OcrResultWords res = engine.run(s.bitmap);
            long dtMs = (System.nanoTime() - t0) / 1_000_000L;
            latencies.add(dtMs);

            String pred = res != null && res.text != null ? res.text : "";
            cerSum += cer(s.groundTruth, pred);
            werSum += wer(s.groundTruth, pred);
            if (res != null && res.meanConfidence != null) {
                confSum += res.meanConfidence;
                confCount++;
            }
        }

        Collections.sort(latencies);
        long p50 = percentile(latencies, 50);
        long p95 = percentile(latencies, 95);

        int n = samples.size();
        double meanConf = confCount == 0 ? 0.0 : confSum / confCount;
        return new OcrEvalReport(cerSum / n, werSum / n, meanConf, p50, p95, n);
    }

    /**
     * Character Error Rate via Levenshtein-Distanz auf <b>Unicode-Codepoints</b>, normalisiert auf
     * die Codepoint-Länge der Ground-Truth. Codepoint-Vergleich vermeidet Fehlmessungen bei
     * Surrogate-Pairs (z.B. Emojis); Diakritika in zusammengesetzter NFC-Form werden als ein
     * Codepoint gewertet — Aufrufer sollten ggf. vor dem Vergleich {@code Normalizer.normalize(s,
     * NFC)} anwenden, falls Eingaben gemischt sind. Bei beiden Strings leer ist die CER 0.
     */
    static double cer(String gt, String pred) {
        if (gt == null) gt = "";
        if (pred == null) pred = "";
        int[] gtCp = toCodepoints(gt);
        int[] prCp = toCodepoints(pred);
        if (gtCp.length == 0) return prCp.length == 0 ? 0.0 : 1.0;
        return (double) levenshteinInts(gtCp, prCp) / gtCp.length;
    }

    /**
     * Word Error Rate via Wort-Levenshtein, normalisiert auf Anzahl gt-Tokens. Tokenisierung
     * unicode-aware via {@code \\p{IsWhite_Space}}; leerer Predict-String und leerer GT-String
     * ergeben WER 0.
     */
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
        // Unicode-Whitespace inkl. NBSP, ideographic space, etc.
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

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0L;
        int idx = (int) Math.ceil((p / 100.0) * sorted.size()) - 1;
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }
}
