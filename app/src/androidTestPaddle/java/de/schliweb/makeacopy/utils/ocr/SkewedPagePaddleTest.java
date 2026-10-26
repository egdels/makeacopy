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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Character error rate of the Paddle engine on the skewed eval pages. Text lines tilted by a degree
 * or two are where axis-aligned detection boxes reach into the neighbouring lines: with them the
 * rates were 0.73 (skew_light) and 0.90 (skew_strong) on a Pixel 7a, with boxes along the lines
 * 0.012 and 0.106.
 */
@RunWith(AndroidJUnit4.class)
public class SkewedPagePaddleTest {

  private static final String TAG = "SkewedPagePaddleTest";

  @Test
  public void skewedPages_characterErrorRate() throws Exception {
    Context ctx = ApplicationProvider.getApplicationContext();
    AssetManager am = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
    OcrEngine engine = PaddleEngineProvider.create(ctx, "eng");
    assertNotNull(engine);
    try {
      String[] names = {"skew_light", "skew_strong"};
      double[] limits = {0.03, 0.2};
      for (int i = 0; i < names.length; i++) {
        String name = names[i];
        Bitmap bm;
        try (InputStream is = am.open("eval/" + name + ".jpg")) {
          bm = BitmapFactory.decodeStream(is);
        }
        String gt;
        try (InputStream is = am.open("eval/" + name + ".gt.txt")) {
          gt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        OCRHelper.OcrResultWords result = engine.run(bm);
        double cer = PaddleVsTesseractEvalTest.cer(normalize(gt), normalize(result.text));
        Log.i(TAG, name + " cer=" + String.format(java.util.Locale.US, "%.4f", cer));
        assertTrue(name + " cer " + cer, cer < limits[i]);
      }
    } finally {
      engine.close();
    }
  }

  private static String normalize(String s) {
    return s == null ? "" : s.replace("\r\n", "\n").replace('\r', '\n').trim();
  }
}
