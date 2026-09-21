package de.schliweb.makeacopy.testutil;

import androidx.test.platform.app.InstrumentationRegistry;
import de.schliweb.makeacopy.data.DocumentSessionRepository;

/**
 * Brings persisted app state into a known condition before a UI test.
 *
 * <p>Gradle installs the app over existing data and only uninstalls it after the run, and {@code am
 * instrument} never does. State from an earlier run or from using the app on the emulator therefore
 * leaks into the next run unless a test resets what it depends on.
 */
public final class TestAppState {

  private TestAppState() {}

  /**
   * Ends the active document session, like "Start new" in the resume dialog: the document stays
   * persisted but is no longer offered for resuming (camera) or restored (export).
   */
  public static void endActiveDocumentSession() {
    DocumentSessionRepository.get(InstrumentationRegistry.getInstrumentation().getTargetContext())
        .endActiveSession(false);
  }
}
