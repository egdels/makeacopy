package de.schliweb.makeacopy.ui.camera;

import static org.junit.Assert.assertEquals;

import de.schliweb.makeacopy.R;
import org.junit.Test;

/** JVM-only tests for where the scan flow continues once an image is available. */
public class CameraScanFlowDestinationTest {

  @Test
  public void withCropping_alwaysGoesToCrop() {
    assertEquals(R.id.navigation_crop, CameraFragment.scanFlowDestination(false, false));
    // "Skip OCR" is applied later, by the crop screen
    assertEquals(R.id.navigation_crop, CameraFragment.scanFlowDestination(false, true));
  }

  @Test
  public void skipCropping_goesToOcr() {
    assertEquals(R.id.navigation_ocr, CameraFragment.scanFlowDestination(true, false));
  }

  @Test
  public void skipCroppingAndOcr_goesStraightToExport() {
    assertEquals(R.id.navigation_export, CameraFragment.scanFlowDestination(true, true));
  }
}
