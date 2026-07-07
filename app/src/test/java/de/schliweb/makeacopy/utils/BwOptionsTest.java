package de.schliweb.makeacopy.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.schliweb.makeacopy.utils.image.BinarizationUtils.BwOptions;
import org.junit.Test;

public class BwOptionsTest {

  @Test
  public void defaults() {
    BwOptions opt = new BwOptions();
    assertEquals(BwOptions.Mode.AUTO_ADAPTIVE, opt.mode);
    assertTrue(opt.useClahe);
    assertTrue(opt.removeShadows);
    assertEquals(0, opt.blockSize);
    assertEquals(0, opt.C);
    assertFalse(opt.gentleMode);
    assertEquals(0, opt.targetDpi);
  }

  @Test
  public void modeEnum_hasTwoValues() {
    BwOptions.Mode[] values = BwOptions.Mode.values();
    assertEquals(2, values.length);
    assertEquals(BwOptions.Mode.AUTO_ADAPTIVE, BwOptions.Mode.valueOf("AUTO_ADAPTIVE"));
    assertEquals(BwOptions.Mode.OTSU_ONLY, BwOptions.Mode.valueOf("OTSU_ONLY"));
  }

  @Test
  public void fieldsAreMutable() {
    BwOptions opt = new BwOptions();
    opt.mode = BwOptions.Mode.OTSU_ONLY;
    opt.useClahe = false;
    opt.removeShadows = false;
    opt.blockSize = 15;
    opt.C = 10;
    opt.gentleMode = true;
    opt.targetDpi = 600;

    assertEquals(BwOptions.Mode.OTSU_ONLY, opt.mode);
    assertFalse(opt.useClahe);
    assertFalse(opt.removeShadows);
    assertEquals(15, opt.blockSize);
    assertEquals(10, opt.C);
    assertTrue(opt.gentleMode);
    assertEquals(600, opt.targetDpi);
  }
}
