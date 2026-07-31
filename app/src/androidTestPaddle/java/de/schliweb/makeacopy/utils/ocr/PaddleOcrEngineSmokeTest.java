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
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Optionaler Smoke-Test für die Paddle-OCR-Engine.
 *
 * <p>Läuft auf jeder ABI: {@code libonnxruntime.so} liegt für arm64-v8a, armeabi-v7a, x86 und
 * x86_64 unter {@code src/main/jniLibs/}, die PP-OCRv5 {@code .ort}-Modelle unter
 * {@code src/paddle/assets/paddleocr/v5/} sind ABI-neutral.
 *
 * <p>Asset {@code eval/clean_scan.jpg} unter {@code app/src/androidTestPaddle/assets/} wird als
 * Eingabe genutzt — kein neues Asset, das nach {@code main/assets/} fließen könnte. Der Zugriff
 * erfolgt über den Instrumentation-Kontext, da Test-Assets im Test-APK liegen.
 */
@RunWith(AndroidJUnit4.class)
public class PaddleOcrEngineSmokeTest {

    @Test
    public void recognize_cleanScan_yieldsTextAndWords() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        Context testCtx = InstrumentationRegistry.getInstrumentation().getContext();
        AssetManager am = testCtx.getAssets();

        Bitmap bm;
        try (InputStream is = am.open("eval/clean_scan.jpg")) {
            bm = BitmapFactory.decodeStream(is);
        }
        assertNotNull("sample bitmap must decode", bm);

        OcrEngine engine = PaddleEngineProvider.create(ctx, "eng");
        assertNotNull(engine);
        try {
            OCRHelper.OcrResultWords result = engine.run(bm);
            assertNotNull(result);
            assertNotNull(result.text);
            assertTrue("text non-empty", result.text.length() > 0);
            assertNotNull("meanConfidence non-null", result.meanConfidence);
            assertTrue("meanConfidence > 0", result.meanConfidence > 0);

            boolean atLeastOneValidBox = false;
            int w = bm.getWidth();
            int h = bm.getHeight();
            for (RecognizedWord rw : result.words) {
                RectF bb = rw.getBoundingBox();
                if (bb == null) continue;
                if (bb.width() > 0
                        && bb.height() > 0
                        && bb.left >= 0
                        && bb.top >= 0
                        && bb.right <= w
                        && bb.bottom <= h) {
                    atLeastOneValidBox = true;
                    break;
                }
            }
            assertTrue("at least one bbox within bitmap bounds", atLeastOneValidBox);
        } finally {
            engine.close();
        }
    }
}
