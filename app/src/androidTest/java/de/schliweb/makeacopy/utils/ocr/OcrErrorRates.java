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

/** Character and word error rates (codepoint-aware) for the OCR eval tests. */
final class OcrErrorRates {

    private OcrErrorRates() {}

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
}
