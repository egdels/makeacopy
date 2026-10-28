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
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * JVM-Unit-Tests für {@link RecDictLoader}.
 *
 * <p>Konvention nach Bugfix (Off-by-one CTC-Decoder): die Datei {@code rec_dict.txt} enthält die
 * Nicht-Blank-Tokens in Klassen-ID 1..N. Der Loader stellt {@code ""} als Blank-Slot an Index 0
 * voran, sodass {@code vocab[argmax]} klassenkorrekt indiziert.
 */
public class RecDictLoaderTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File writeDict(String content) throws IOException {
        File f = tmp.newFile("dict.txt");
        try (OutputStreamWriter w =
                new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(content);
        }
        return f;
    }

    @Test
    public void load_prependsBlankSlot_andPreservesOrder() throws Exception {
        File f = writeDict("a\nb\nc\n");
        String[] vocab = RecDictLoader.load(f);
        assertArrayEquals(new String[] {"", "a", "b", "c"}, vocab);
    }

    @Test
    public void load_doesNotTrim_andSkipsOnlyTrulyEmptyLines() throws Exception {
        // Konvention nach CTC-Space-Fix: kein trim — sonst verschwindet das letzte Vocab-Token
        // " " (Space) der PP-OCRv5-Modelle. Nur length==0 Zeilen werden übersprungen.
        File f = writeDict("  a  \n\nb\n");
        String[] vocab = RecDictLoader.load(f);
        assertArrayEquals(new String[] {"", "  a  ", "b"}, vocab);
    }

    @Test
    public void load_preservesTrailingSpaceLine() throws Exception {
        // PP-OCRv5 rec_dict.txt hat als letztes Token genau " " (single space).
        File f = writeDict("a\nb\n \n");
        String[] vocab = RecDictLoader.load(f);
        assertArrayEquals(new String[] {"", "a", "b", " "}, vocab);
        assertEquals(" ", vocab[vocab.length - 1]);
    }

    @Test
    public void load_vocabSize_isDictLinesPlusOne() throws Exception {
        // Letzte Zeile ist " " — muss mitgezählt werden.
        File f = writeDict("x\ny\nz\n \n");
        String[] vocab = RecDictLoader.load(f);
        assertEquals(5, vocab.length);
    }

    @Test
    public void load_indices_areOneBased_blankAtZero() throws Exception {
        File f = writeDict("A\nB\nC\n");
        String[] vocab = RecDictLoader.load(f);
        assertEquals(4, vocab.length);
        assertEquals("", vocab[0]);
        assertEquals("A", vocab[1]);
        assertEquals("C", vocab[3]);
    }

    @Test
    public void load_utf8MultiByteToken() throws Exception {
        File f = writeDict("ä\nß\n€\n");
        String[] vocab = RecDictLoader.load(f);
        assertArrayEquals(new String[] {"", "ä", "ß", "€"}, vocab);
    }
}
