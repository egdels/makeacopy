package de.schliweb.makeacopy.ui.camera;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.testutil.HiltFragmentScenario;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Confirming the camera options must leave the export option "Export OCR as separate TXT"
 * ("include_ocr") alone. It used to be overwritten with {@code !skipOcr}, which switched the TXT
 * export on for everyone who confirmed the camera options with OCR enabled.
 */
@RunWith(AndroidJUnit4.class)
public class CameraOptionsKeepTxtExportOptionTest {

  @Rule
  public GrantPermissionRule camPerm =
      GrantPermissionRule.grant(android.Manifest.permission.CAMERA);

  private SharedPreferences prefs;
  private Map<String, ?> before;

  @Before
  public void rememberPrefs() {
    Context ctx = ApplicationProvider.getApplicationContext();
    prefs = ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
    before = new HashMap<>(prefs.getAll());
  }

  @After
  public void restorePrefs() {
    SharedPreferences.Editor editor = prefs.edit().clear();
    for (Map.Entry<String, ?> e : before.entrySet()) {
      Object v = e.getValue();
      if (v instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) v);
      else if (v instanceof String) editor.putString(e.getKey(), (String) v);
      else if (v instanceof Integer) editor.putInt(e.getKey(), (Integer) v);
      else if (v instanceof Long) editor.putLong(e.getKey(), (Long) v);
      else if (v instanceof Float) editor.putFloat(e.getKey(), (Float) v);
    }
    editor.commit();
  }

  /**
   * Opens the options from the camera screen, so that both writers are covered: the dialog itself
   * and the camera screen's handling of the dialog result.
   */
  private void confirmCameraOptions() {
    HiltFragmentScenario.launchInHiltContainer(
        CameraFragment.class, null, R.style.Theme_MakeACopy, Lifecycle.State.RESUMED);
    onView(withId(R.id.button_camera_options)).perform(click());
    onView(withText(R.string.confirm)).inRoot(isDialog()).perform(click());
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  @Test
  public void confirmWithOcrEnabled_doesNotSwitchTxtExportOn() {
    prefs.edit().putBoolean("skip_ocr", false).remove("include_ocr").commit();

    confirmCameraOptions();

    assertFalse("skip_ocr stays off", prefs.getBoolean("skip_ocr", true));
    assertFalse("TXT export keeps its default (off)", prefs.contains("include_ocr"));
  }

  @Test
  public void confirmWithSkipOcr_doesNotSwitchTxtExportOff() {
    prefs.edit().putBoolean("skip_ocr", true).putBoolean("include_ocr", true).commit();

    confirmCameraOptions();

    assertTrue("skip_ocr stays on", prefs.getBoolean("skip_ocr", false));
    assertTrue("the user's TXT export choice is kept", prefs.getBoolean("include_ocr", false));
  }
}
