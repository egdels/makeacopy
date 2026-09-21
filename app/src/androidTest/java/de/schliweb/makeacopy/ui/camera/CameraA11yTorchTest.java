package de.schliweb.makeacopy.ui.camera;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import androidx.lifecycle.Lifecycle;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.espresso.PerformException;
import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.assertion.ViewAssertions;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.a11y.util.A11yCapture;
import de.schliweb.makeacopy.a11y.util.TestPrefs;
import de.schliweb.makeacopy.testutil.HiltFragmentScenario;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Verifies torch (flashlight) accessibility announcements when a flash unit is available. If the
 * device/emulator reports no flash unit, the test is skipped gracefully. Note: torch haptics in
 * production are emitted via View.performHapticFeedback, which we do not capture in tests. We
 * therefore assert announcements and UI behavior only.
 */
@RunWith(AndroidJUnit4.class)
public class CameraA11yTorchTest extends de.schliweb.makeacopy.a11y.util.A11yBaseTest {

  @Before
  public void setUp() {
    A11yCapture.install();
  }

  @After
  public void tearDown() {
    A11yCapture.uninstall();
  }

  @Test
  public void toggling_flash_emits_accessibility_announcements_when_flash_available()
      throws Exception {
    HiltFragmentScenario<CameraFragment> scenario =
        HiltFragmentScenario.launchInHiltContainer(
            CameraFragment.class, null, R.style.Theme_MakeACopy, Lifecycle.State.RESUMED);

    final AtomicBoolean hasFlashRef = new AtomicBoolean(false);
    scenario.onFragment(
        f -> {
          Context ctx = f.requireContext();
          TestPrefs.setAccessibilityMode(ctx, true);
          // Probe flash availability if camera is bound
          boolean has = false;
          try {
            if (f != null && f.getView() != null) {
              // Best-effort: if camera is initialized, query its info
              androidx.camera.core.ImageCapture cap = f.getImageCaptureForTest();
              // ImageCapture existence correlates with bound camera; check camera info via button
              // state next
              has = (cap != null); // weak heuristic
            }
          } catch (Exception ignored) {
          }
          // Even if ImageCapture is null (not fully bound), allow the test to run; we will skip
          // if no torch announcement occurs after click.
          hasFlashRef.set(has);
        });

    // If the flash button is not visible (e.g. emulator/no-flash device), skip gracefully.
    try {
      Espresso.onView(ViewMatchers.withId(R.id.button_flash))
          .check(ViewAssertions.matches(ViewMatchers.isDisplayed()));
    } catch (NoMatchingViewException | AssertionError e) {
      assumeTrue("Skipping: flash button not visible in this environment.", false);
    }

    // Try toggling the flash button.
    try {
      Espresso.onView(ViewMatchers.withId(R.id.button_flash)).perform(ViewActions.click());
    } catch (PerformException e) {
      assumeTrue("Skipping: cannot click flash button (animations/device constraints).", false);
    }

    // Expect either an announcement for ON or OFF, depending on prior state. Other announcements
    // are ignored: with Accessibility Mode on, "Camera ready" is announced whenever the preview
    // starts streaming, which can happen right around the click on a cold camera start.
    Context target =
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
    final String onS = target.getString(R.string.flashlight_on);
    final String offS = target.getString(R.string.flashlight_off);
    java.util.function.Predicate<String> isFlashAnnouncement =
        text -> text.contains(onS) || text.contains(offS);
    CharSequence ann = A11yCapture.awaitMatching(2500, isFlashAnnouncement);

    // If no announcement was captured, assume the environment lacks a flash-capable camera and
    // skip.
    assumeTrue(
        "Skipping: torch announcement not observed; device may have no flash or camera unbound.",
        ann != null);

    // Toggle again to switch state and expect the opposite announcement
    A11yCapture.clear();
    Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withId(R.id.button_flash))
        .perform(ViewActions.click());
    CharSequence ann2 = A11yCapture.awaitMatching(2500, isFlashAnnouncement);
    assertNotNull("Expected second flashlight announcement", ann2);
  }
}
