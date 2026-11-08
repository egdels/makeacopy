package de.schliweb.makeacopy.ui.camera;

import static org.junit.Assert.assertEquals;

import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.ui.camera.IncomingShareRouter.Route;
import org.junit.Test;

/** A file shared into the running app must never silently replace work in progress. */
public class IncomingShareRouterTest {

  @Test
  public void openDocument_asksWhereverTheUserIs() {
    assertEquals(Route.ASK_ADD_OR_NEW, IncomingShareRouter.decide(1, R.id.navigation_export));
    assertEquals(Route.ASK_ADD_OR_NEW, IncomingShareRouter.decide(3, R.id.navigation_camera));
    assertEquals(Route.ASK_ADD_OR_NEW, IncomingShareRouter.decide(2, R.id.navigation_crop));
    assertEquals(Route.ASK_ADD_OR_NEW, IncomingShareRouter.decide(2, null));
  }

  @Test
  public void pageInProgressWithoutDocument_asksBeforeDiscarding() {
    assertEquals(Route.ASK_DISCARD_PAGE, IncomingShareRouter.decide(0, R.id.navigation_crop));
    assertEquals(Route.ASK_DISCARD_PAGE, IncomingShareRouter.decide(0, R.id.navigation_ocr));
    assertEquals(Route.ASK_DISCARD_PAGE, IncomingShareRouter.decide(0, R.id.navigation_review));
  }

  @Test
  public void nothingOpen_importsDirectly() {
    assertEquals(Route.IMPORT, IncomingShareRouter.decide(0, R.id.navigation_camera));
    assertEquals(Route.IMPORT, IncomingShareRouter.decide(0, R.id.navigation_export));
    assertEquals(Route.IMPORT, IncomingShareRouter.decide(0, R.id.navigation_collections));
    assertEquals(Route.IMPORT, IncomingShareRouter.decide(0, null));
  }
}
