package de.schliweb.makeacopy.ui.ocr;

import static de.schliweb.makeacopy.ui.ocr.OCRFragment.OCR_MODE_ORIGINAL;
import static de.schliweb.makeacopy.ui.ocr.OCRFragment.OCR_MODE_PADDLE;
import static de.schliweb.makeacopy.ui.ocr.OCRFragment.OCR_MODE_QUICK;
import static de.schliweb.makeacopy.ui.ocr.OCRFragment.OCR_MODE_ROBUST;
import static org.junit.Assert.assertEquals;

import de.schliweb.makeacopy.R;
import org.junit.Test;

/** JVM-only tests for the mode decisions of the OCR prep mode dialog. */
public class OcrPrepModeDialogTest {

  @Test
  public void picker_showsHiddenQuickAsRobust() {
    assertEquals(OCR_MODE_ROBUST, OCRFragment.prepModeForPicker(OCR_MODE_QUICK, false, false));
    assertEquals(OCR_MODE_ORIGINAL, OCRFragment.prepModeForPicker(OCR_MODE_ORIGINAL, false, false));
    assertEquals(OCR_MODE_ROBUST, OCRFragment.prepModeForPicker(OCR_MODE_ROBUST, false, false));
  }

  @Test
  public void picker_clampsUnknownSavedModes() {
    assertEquals(OCR_MODE_ORIGINAL, OCRFragment.prepModeForPicker(-5, false, false));
    assertEquals(OCR_MODE_PADDLE, OCRFragment.prepModeForPicker(99, false, true));
  }

  @Test
  public void picker_paddleOnlyWhileItCanBeChosen() {
    assertEquals(OCR_MODE_ROBUST, OCRFragment.prepModeForPicker(OCR_MODE_PADDLE, false, false));
    assertEquals(OCR_MODE_PADDLE, OCRFragment.prepModeForPicker(OCR_MODE_PADDLE, false, true));
    assertEquals(OCR_MODE_PADDLE, OCRFragment.prepModeForPicker(OCR_MODE_PADDLE, true, false));
  }

  @Test
  public void radio_mapsToMode() {
    assertEquals(OCR_MODE_ORIGINAL, OCRFragment.prepModeForRadioId(R.id.rbtn_mode_original, false));
    assertEquals(OCR_MODE_ROBUST, OCRFragment.prepModeForRadioId(R.id.rbtn_mode_robust, false));
    assertEquals(OCR_MODE_ROBUST, OCRFragment.prepModeForRadioId(R.id.rbtn_mode_quick, false));
    assertEquals(OCR_MODE_ROBUST, OCRFragment.prepModeForRadioId(-1, true));
  }

  @Test
  public void radio_paddleNeedsTheVisibleToggle() {
    assertEquals(OCR_MODE_PADDLE, OCRFragment.prepModeForRadioId(R.id.rbtn_mode_paddle, true));
    assertEquals(OCR_MODE_ROBUST, OCRFragment.prepModeForRadioId(R.id.rbtn_mode_paddle, false));
  }

  @Test
  public void toastLine_format() {
    StringBuilder sb = new StringBuilder("Mode set");
    OCRFragment.appendOptionState(sb, "Auto rotate", true);
    OCRFragment.appendOptionState(sb, "Post-processing", false);
    assertEquals("Mode set\nAuto rotate: [ON]\nPost-processing: [OFF]", sb.toString());
  }
}
