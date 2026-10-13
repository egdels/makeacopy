package de.schliweb.makeacopy.utils.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests where the PDF text layer puts its baseline inside an OCR box. */
public class PdfCreatorBaselineTest {

  @Test
  public void baselineSitsInsideTheBoxAboveItsBottom() {
    float top = 200f;
    float bottom = 240f;
    float baseline = PdfCreator.baselineInBox(bottom, bottom - top);
    assertEquals(232f, baseline, 1e-4f);
    // Image Y grows downward: inside the box means between top and bottom, never below it.
    assertTrue(baseline > top);
    assertTrue(baseline < bottom);
  }
}
