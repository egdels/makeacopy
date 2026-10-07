package de.schliweb.makeacopy.ui.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.SurfaceOrientedMeteringPointFactory;
import org.junit.Test;

/** Pre-capture 3A action: AF only with autofocus, short auto-cancel window in both modes. */
public class CameraPreCaptureMeteringActionTest {

  private static MeteringPoint center() {
    return new SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f);
  }

  @Test
  public void autofocus_locksAfAeAwb() {
    FocusMeteringAction a = CameraFragment.buildPreCaptureMeteringAction(center(), false);
    assertEquals(1, a.getMeteringPointsAf().size());
    assertEquals(1, a.getMeteringPointsAe().size());
    assertEquals(1, a.getMeteringPointsAwb().size());
  }

  @Test
  public void manualFocus_skipsAfButKeepsAeAwb() {
    FocusMeteringAction a = CameraFragment.buildPreCaptureMeteringAction(center(), true);
    assertTrue(a.getMeteringPointsAf().isEmpty());
    assertEquals(1, a.getMeteringPointsAe().size());
    assertEquals(1, a.getMeteringPointsAwb().size());
  }

  @Test
  public void autoCancelWindowIsShortInBothModes() {
    for (boolean manual : new boolean[] {false, true}) {
      FocusMeteringAction a = CameraFragment.buildPreCaptureMeteringAction(center(), manual);
      assertTrue(a.isAutoCancelEnabled());
      assertEquals(CameraFragment.PRE_CAPTURE_AF_TIMEOUT_MS, a.getAutoCancelDurationInMillis());
      assertFalse(a.getAutoCancelDurationInMillis() > 1000L);
    }
  }
}
