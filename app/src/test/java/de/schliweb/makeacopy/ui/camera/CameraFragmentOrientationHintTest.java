/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import de.schliweb.makeacopy.framing.FramingResult;
import de.schliweb.makeacopy.framing.GuidanceHint;
import org.junit.Test;

/** Unit tests for the orientation hint injected into the A11y guidance when no document shows. */
public class CameraFragmentOrientationHintTest {

  private static FramingResult framing(boolean hasDocument) {
    return new FramingResult(0.4f, 0.1f, -0.2f, 0.7f, 1f, 2f, null, hasDocument);
  }

  @Test
  public void noFramingResult_staysNull() {
    assertNull(CameraFragment.withOrientationHint(null, 90, 0.9));
  }

  @Test
  public void documentVisible_resultIsUnchanged() {
    FramingResult fr = framing(true);
    assertSame(fr, CameraFragment.withOrientationHint(fr, 90, 0.9));
  }

  @Test
  public void lowOrientationConfidence_resultIsUnchanged() {
    FramingResult fr = framing(false);
    assertSame(fr, CameraFragment.withOrientationHint(fr, 90, 0.29));
    assertSame(fr, CameraFragment.withOrientationHint(fr, -1, -1.0));
  }

  @Test
  public void noDocumentAndConfidentOrientation_getsTheMatchingTip() {
    FramingResult landscape = CameraFragment.withOrientationHint(framing(false), 90, 0.30);
    assertEquals(GuidanceHint.ORIENTATION_LANDSCAPE_TIP, landscape.hint);
    assertFalse(landscape.hasDocument);
    assertEquals(0.4f, landscape.quality, 0f);
    assertEquals(-0.2f, landscape.dyNorm, 0f);

    FramingResult portrait = CameraFragment.withOrientationHint(framing(false), 0, 0.8);
    assertEquals(GuidanceHint.ORIENTATION_PORTRAIT_TIP, portrait.hint);
  }
}
