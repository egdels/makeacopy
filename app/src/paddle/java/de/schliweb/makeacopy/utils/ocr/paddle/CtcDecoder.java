/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr.paddle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class provides functionality for greedy decoding based on the Connectionist Temporal
 * Classification (CTC) algorithm. It processes a sequence of probabilities (logits) over time
 * steps and outputs a decoded sequence of tokens while merging repeated predictions and removing
 * blank tokens.
 *
 * The class includes helper records and methods for managing recognized tokens, frame ranges,
 * and confidence calculations as part of the decoding process.
 */
final class CtcDecoder {

    private CtcDecoder() {}

    /**
     * Represents a recognized token with its associated metadata, such as the token text, confidence score,
     * and the range of frames within which the token occurs.
     *
     * Instances of this record are immutable and useful for storing the results of token recognition processes,
     * particularly in tasks such as speech recognition or optical character recognition (OCR).
     *
     * Fields:
     * - `text`: The string representation of the recognized token.
     * - `confidence`: A float indicating the confidence score for the recognition of this token, typically in the range [0.0, 1.0].
     * - `frameStart`: An integer indicating the starting frame index where the token is detected.
     * - `frameEnd`: An integer indicating the ending frame index where the token is detected.
     */
    record RecognizedToken(String text, float confidence, int frameStart, int frameEnd) {}

    /**
     * Represents the result of a decoding operation, such as those used in speech or text recognition.
     * The class includes the decoded text, its confidence score, the indices of tokens included in the
     * decoding, the recognized tokens and their metadata, and the frame count used in the recognition process.
     *
     * Fields:
     * - `text`: The decoded string representation of the recognized content.
     * - `meanConfidence`: A float representing the average confidence score for the decoding, typically
     *   calculated over frames or tokens.
     * - `tokenIndices`: An array of integers indicating the indices of the tokens that were part of the decoded result.
     * - `tokens`: A list of recognized tokens that provide additional metadata such as confidence,
     *   token text, and temporal frame boundaries.
     * - `frameCount`: The total number of frames across which the decoding was performed.
     *
     * This record is immutable and serves as a consolidated representation of decoding output, often used
     * in natural language processing or recognition systems.
     */
    record Decoded(
            String text,
            float meanConfidence,
            int[] tokenIndices,
            List<RecognizedToken> tokens,
            int frameCount,
            int[] charFrames) {

        Decoded(
                String text,
                float meanConfidence,
                int[] tokenIndices,
                List<RecognizedToken> tokens,
                int frameCount) {
            this(text, meanConfidence, tokenIndices, tokens, frameCount, null);
        }
    }

    /**
     * Decodes the output logits of a time-distributed model into a text sequence
     * and associated metadata, such as token indices, confidence scores, and recognized tokens.
     *
     * The input logits represent the output probabilities (or scores) across time frames (T) for a given vocabulary (C).
     * The method performs a decoding operation using a greedy algorithm, identifying the token with the highest
     * probability in each time frame, and applying additional rules to filter repeated or blank tokens.
     *
     * @param logitsTW A 2D array of shape [T, C], where T is the number of time frames and C is the vocabulary size.
     *                 Each row represents the model's logits or probabilities for the tokens in the vocabulary at a specific time frame.
     * @param vocab An array of strings representing the vocabulary, where the index corresponds to the token ID.
     *              The first entry (index 0) is reserved for the blank token.
     * @return A {@code Decoded} object containing the decoded text, its confidence, token indices, recognized tokens,
     *         and the total frame count.
     * @throws IllegalArgumentException If the vocabulary is null or empty.
     */
    static Decoded decode(float[][] logitsTW, String[] vocab) {
        if (logitsTW == null || logitsTW.length == 0) {
            return new Decoded("", 0f, new int[0], Collections.emptyList(), 0);
        }
        if (vocab == null || vocab.length == 0) {
            throw new IllegalArgumentException("vocab must be non-empty");
        }
        final int T = logitsTW.length;
        final int C = logitsTW[0].length;

        StringBuilder sb = new StringBuilder();
        int[] tokenBuf = new int[T];
        int tokenCount = 0;
        // Frame index per char of the emitted text (a multi-char vocab entry repeats its frame),
        // so that callers can place every character horizontally without guessing.
        int[] frameOfChar = new int[T * 4];
        int charCount = 0;
        double confSum = 0.0;
        int confCount = 0;
        int prevIdx = -1;

        // Sub-Word-Aufbau: aktueller Wort-Puffer.
        StringBuilder curWord = new StringBuilder();
        double curConfSum = 0.0;
        int curConfCount = 0;
        int curFrameStart = -1;
        int curFrameEnd = -1;
        List<RecognizedToken> tokens = new ArrayList<>();

        for (int t = 0; t < T; t++) {
            float[] row = logitsTW[t];
            // Argmax
            int argmax = 0;
            float maxLogit = row[0];
            for (int c = 1; c < C; c++) {
                if (row[c] > maxLogit) {
                    maxLogit = row[c];
                    argmax = c;
                }
            }
            // Akzeptiere wenn nicht Blank und nicht Wiederholung des Vorgängers.
            boolean isBlank = (argmax == 0);
            boolean isRepeat = (argmax == prevIdx);
            if (!isBlank && !isRepeat) {
                // Auto-Detektion: liegt der Max-Wert bereits in (0..1] und summiert die Zeile zu
                // ungefähr 1, behandeln wir den Output als Softmax-Wahrscheinlichkeiten und
                // verwenden direkt row[argmax] als Konfidenz. Andernfalls numerisch stabilen
                // Softmax-Max über row[c] - maxLogit berechnen.
                double prob;
                if (maxLogit >= 0f && maxLogit <= 1.0001f) {
                    double rowSum = 0.0;
                    for (int c = 0; c < C; c++) {
                        rowSum += row[c];
                    }
                    if (rowSum > 0.99 && rowSum < 1.01) {
                        prob = maxLogit;
                    } else {
                        double sumExp = 0.0;
                        for (int c = 0; c < C; c++) {
                            sumExp += Math.exp(row[c] - maxLogit);
                        }
                        prob = 1.0 / sumExp;
                    }
                } else {
                    double sumExp = 0.0;
                    for (int c = 0; c < C; c++) {
                        sumExp += Math.exp(row[c] - maxLogit);
                    }
                    prob = 1.0 / sumExp;
                }
                confSum += prob;
                confCount++;

                String tokStr = (argmax < vocab.length) ? vocab[argmax] : "";
                if (tokStr != null) {
                    sb.append(tokStr);
                    for (int k = 0; k < tokStr.length(); k++) {
                        if (charCount == frameOfChar.length) {
                            frameOfChar = java.util.Arrays.copyOf(frameOfChar, charCount * 2);
                        }
                        frameOfChar[charCount++] = t;
                    }
                }
                tokenBuf[tokenCount++] = argmax;

                // Sub-Word-Logik: ist das Token Whitespace, dann aktuelles Wort abschließen
                // (sofern nicht-leer) und keinen neuen Frame-Range starten.
                if (tokStr != null && isWhitespaceToken(tokStr)) {
                    flushWord(tokens, curWord, curConfSum, curConfCount, curFrameStart, curFrameEnd);
                    curWord.setLength(0);
                    curConfSum = 0.0;
                    curConfCount = 0;
                    curFrameStart = -1;
                    curFrameEnd = -1;
                } else if (tokStr != null && !tokStr.isEmpty()) {
                    if (curFrameStart < 0) curFrameStart = t;
                    curFrameEnd = t;
                    curWord.append(tokStr);
                    curConfSum += prob;
                    curConfCount++;
                }
            }
            prevIdx = argmax;
        }

        // Letztes offenes Wort abschließen.
        flushWord(tokens, curWord, curConfSum, curConfCount, curFrameStart, curFrameEnd);

        float meanConf = (confCount == 0) ? 0f : (float) (confSum / confCount);
        int[] indices = new int[tokenCount];
        System.arraycopy(tokenBuf, 0, indices, 0, tokenCount);
        return new Decoded(
                sb.toString(),
                meanConf,
                indices,
                Collections.unmodifiableList(tokens),
                T,
                java.util.Arrays.copyOf(frameOfChar, charCount));
    }

    /**
     * Determines whether the given string consists entirely of whitespace characters.
     *
     * @param s The string to check. May be null or empty.
     * @return {@code true} if the string is non-null, not empty, and all of its characters are whitespace; {@code false} otherwise.
     */
    private static boolean isWhitespaceToken(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (!Character.isWhitespace(cp)) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static void flushWord(
            List<RecognizedToken> tokens,
            StringBuilder buf,
            double confSum,
            int confCount,
            int frameStart,
            int frameEnd) {
        if (buf.length() == 0 || confCount == 0) return;
        float wConf = (float) (confSum / confCount);
        tokens.add(new RecognizedToken(buf.toString(), wConf, frameStart, frameEnd));
    }
}
