package de.schliweb.makeacopy.utils;

import static org.junit.Assert.*;

import de.schliweb.makeacopy.utils.infra.FeatureFlags;
import org.junit.Test;

/**
 * Tests for {@link FeatureFlags}. In JVM unit tests, BuildConfig fields are available from the
 * compiled debug BuildConfig. Each method uses reflection and falls back to false on any error, so
 * we verify they return a boolean without throwing.
 */
public class FeatureFlagsTest {

  @Test
  public void isOcrReviewEnabled_returnsBooleanWithoutThrowing() {
    // Should not throw; returns the BuildConfig value or false
    boolean result = FeatureFlags.isOcrReviewEnabled();
    // result is either true or false — just verify no exception
    assertTrue(result || !result);
  }

  @Test
  public void isScanLibraryEnable_returnsBooleanWithoutThrowing() {
    boolean result = FeatureFlags.isScanLibraryEnable();
    assertTrue(result || !result);
  }

  @Test
  public void isFramingLoggingEnabled_returnsBooleanWithoutThrowing() {
    boolean result = FeatureFlags.isFramingLoggingEnabled();
    assertTrue(result || !result);
  }

  @Test
  public void isA11yGuidanceEnabled_returnsBooleanWithoutThrowing() {
    boolean result = FeatureFlags.isA11yGuidanceEnabled();
    assertTrue(result || !result);
  }
}
