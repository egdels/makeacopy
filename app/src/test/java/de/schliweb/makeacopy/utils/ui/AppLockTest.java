package de.schliweb.makeacopy.utils.ui;

import static org.junit.Assert.*;

import org.junit.Test;

/** When the optional app lock asks again (security audit F-07). */
public class AppLockTest {

  private static final long T = AppLock.TIMEOUT_MS;

  @Test
  public void off_neverAsks() {
    assertFalse(AppLock.needsUnlock(false, false, -1, 0));
    assertFalse(AppLock.needsUnlock(false, true, 0, 10 * T));
  }

  @Test
  public void on_asksUntilUnlocked() {
    // A new process starts locked
    assertTrue(AppLock.needsUnlock(true, false, -1, 0));
  }

  @Test
  public void unlocked_staysUnlockedInTheForeground() {
    assertFalse(AppLock.needsUnlock(true, true, -1, 5 * T));
  }

  @Test
  public void shortAbsence_doesNotAsk_longAbsenceDoes() {
    long away = 1_000;
    assertFalse(AppLock.needsUnlock(true, true, away, away + T - 1));
    assertTrue(AppLock.needsUnlock(true, true, away, away + T));
    assertTrue(AppLock.needsUnlock(true, true, away, away + 10 * T));
  }
}
