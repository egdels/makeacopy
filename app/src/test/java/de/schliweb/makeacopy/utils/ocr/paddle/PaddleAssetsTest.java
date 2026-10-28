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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * JVM unit tests for the pure hash logic of {@link PaddleAssets}. These tests do not
 * require an Android {@code Context} and therefore run in the standard
 * {@code testDebugUnitTest} task.
 */
public class PaddleAssetsTest {

    /**
     * Test 1: SHA256 helper produces the well-known hash for a small byte array.
     * "abc" -> ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
     */
    @Test
    public void sha256OfBytes_matchesKnownVector() {
        String hash = PaddleAssets.sha256OfBytes("abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                hash);
    }

    /** SHA256 of the empty input must equal the canonical empty-string hash. */
    @Test
    public void sha256OfBytes_emptyInput() {
        String hash = PaddleAssets.sha256OfBytes(new byte[0]);
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                hash);
    }

    /** Test 2a: verifyOrThrow returns silently when the hash matches. */
    @Test
    public void verifyOrThrow_acceptsMatch() throws IOException {
        byte[] data = "abc".getBytes(StandardCharsets.US_ASCII);
        PaddleAssets.verifyOrThrow(
                data,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "abc");
        // Also accept upper-case form of the expected hash (case-insensitive).
        PaddleAssets.verifyOrThrow(
                data,
                "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
                "abc-upper");
    }

    /** Test 2b: verifyOrThrow throws on mismatch and surfaces both hashes. */
    @Test
    public void verifyOrThrow_throwsOnMismatch() {
        byte[] data = "abc".getBytes(StandardCharsets.US_ASCII);
        IOException ex = assertThrows(IOException.class, () ->
                PaddleAssets.verifyOrThrow(data, "0".repeat(64), "abc"));
        String msg = ex.getMessage();
        assertTrue("message should mention label, got: " + msg,
                msg != null && msg.contains("abc"));
        assertTrue("message should mention expected hash, got: " + msg,
                msg.contains("0".repeat(64)));
        assertTrue("message should mention actual hash, got: " + msg,
                msg.contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
    }

    /**
     * Asset-Konstanten der Multi-Model-API: DET_NAME zeigt auf {@code det.ort},
     * SHA256SUMS-Asset hat den richtigen Namen.
     */
    @Test
    public void assetConstants_areMultiModel() {
        assertEquals("det.ort", PaddleAssets.DET_NAME);
        assertEquals("SHA256SUMS", PaddleAssets.SHA256SUMS_NAME);
        assertEquals("paddleocr/v5", PaddleAssets.ASSET_DIR);
    }
}
