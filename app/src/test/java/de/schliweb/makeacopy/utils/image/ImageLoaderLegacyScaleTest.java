package de.schliweb.makeacopy.utils.image;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Corners stored before the decode cap must map onto today's decode of the same original. */
public class ImageLoaderLegacyScaleTest {

  @Test
  public void imagesTheOldSamplingAlreadyKeptSmall_keepTheirCoordinates() {
    assertEquals(1.0, ImageLoader.legacyDecodeScale(4032, 3024), 0); // 12 MP
    assertEquals(1.0, ImageLoader.legacyDecodeScale(4096, 4096), 0);
  }

  @Test
  public void fiftyMegapixel_wasDecodedAtFullSize() {
    // Half of 8160 is below 4096, so the old sampling decoded the photo at full size.
    assertEquals(4096 / 8160.0, ImageLoader.legacyDecodeScale(8160, 6120), 1e-12);
    assertEquals(4096 / 8160.0, ImageLoader.legacyDecodeScale(6120, 8160), 1e-12);
    assertEquals(4096 / 8000.0, ImageLoader.legacyDecodeScale(8000, 6000), 1e-12);
  }

  @Test
  public void twoHundredMegapixel_wasSampledByTwoButStillAboveTheCap() {
    assertEquals(4096 / 8160.0, ImageLoader.legacyDecodeScale(16320, 12240), 1e-12);
  }

  @Test
  public void sampledByTwo_canStillExceedTheCap() {
    assertEquals(4096 / 4500.0, ImageLoader.legacyDecodeScale(9000, 9000), 1e-12);
  }

  @Test
  public void oddSizes_roundTheSampledEdgeUp() {
    assertEquals(4096 / 8193.0, ImageLoader.legacyDecodeScale(16385, 9000), 1e-12);
  }

  @Test
  public void missingBounds_meanNoScale() {
    assertEquals(1.0, ImageLoader.legacyDecodeScale(0, 0), 0);
    assertEquals(1.0, ImageLoader.legacyDecodeScale(-1, 100), 0);
  }
}
