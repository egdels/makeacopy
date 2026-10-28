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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for loading vocabulary data from a given file.
 *
 * This class provides a method to load and parse tokens from a file, ensuring
 * compatibility with PaddleOCR's requirements. The vocabulary file should
 * consist of tokens separated by line breaks, excluding the CTC blank slot,
 * which is automatically added as the first entry in the returned token array.
 *
 * The class is immutable and cannot be instantiated. It is meant to be used
 * only through its static methods.
 */
final class RecDictLoader {

    private RecDictLoader() {}

    /**
     * Loads vocabulary tokens from a specified file.
     *
     * This method reads a file containing vocabulary entries, where each line
     * represents a single token. The first entry in the returned token array is
     * always an empty string to represent the CTC blank slot, which is not explicitly
     * included in the file. Lines that are empty or consist only of whitespace are
     * ignored, but tokens containing spaces are preserved as-is.
     *
     * The file is expected to use UTF-8 encoding, and the method ensures proper
     * resource handling by using a try-with-resources construct.
     *
     * @param dict The file containing vocabulary tokens to load. Cannot be null.
     * @return An array of strings where each element represents a token from the file.
     *         The first element is always an empty string (CTC blank slot).
     * @throws IllegalArgumentException If the specified file is null.
     * @throws IOException If an error occurs while reading the file.
     */
    static String[] load(File dict) throws IOException {
        if (dict == null) {
            throw new IllegalArgumentException("dict must be non-null");
        }
        List<String> tokens = new ArrayList<>();
        // Index 0: CTC-Blank-Slot. PaddleOCR konvertiert Klasse 0 → Blank, die Datei
        // {@code rec_dict.txt} enthält dagegen nur die Nicht-Blank-Tokens (Klassen 1..N).
        // Ohne diesen Pad würde {@code vocab[argmax]} systematisch das Token der nächst-
        // höheren Klasse liefern (Off-by-one) — sichtbar in den Debug-Dumps als „Mpsfn"
        // statt „Lorem", da dann z.B. Klasse 22 = `L` fälschlich zu `vocab[22]` = `M` wird.
        tokens.add("");
        try (BufferedReader br =
                new BufferedReader(
                        new InputStreamReader(new FileInputStream(dict), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                // WICHTIG: nicht trimmen — die letzte Vocab-Zeile ist " " (Space)
                // und würde durch trim() zu "" reduziert. Dadurch ginge das Space-Token
                // verloren und wer bliebe schlecht.
                // Wir verwerfen nur echte Leer-Zeilen (length==0).
                if (line.isEmpty()) {
                    continue;
                }
                tokens.add(line);
            }
        }
        return tokens.toArray(new String[0]);
    }
}
