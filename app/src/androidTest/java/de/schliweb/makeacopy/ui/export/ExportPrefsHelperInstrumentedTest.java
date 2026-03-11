package de.schliweb.makeacopy.ui.export;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ExportPrefsHelperInstrumentedTest {

  private Context context;

  @Before
  public void setUp() {
    context = ApplicationProvider.getApplicationContext();
    // Clear preferences before each test
    SharedPreferences prefs = ExportPrefsHelper.getPrefs(context);
    prefs.edit().clear().commit();
  }

  @Test
  public void testLastImportUriPersistence() {
    String testUri = "content://com.android.providers.media.documents/document/image%3A123";

    // Initially null
    assertNull(ExportPrefsHelper.getLastImportUri(context));

    // Set and retrieve
    ExportPrefsHelper.setLastImportUri(context, testUri);
    assertEquals(testUri, ExportPrefsHelper.getLastImportUri(context));
  }

  @Test
  public void testLastExportUriPersistence() {
    String testUri = "content://com.android.externalstorage.documents/tree/primary%3ADownloads";

    // Initially null
    assertNull(ExportPrefsHelper.getLastExportUri(context));

    // Set and retrieve
    ExportPrefsHelper.setLastExportUri(context, testUri);
    assertEquals(testUri, ExportPrefsHelper.getLastExportUri(context));
  }

  @Test
  public void testUrisAreIndependent() {
    String importUri = "content://import/path";
    String exportUri = "content://export/path";

    ExportPrefsHelper.setLastImportUri(context, importUri);
    ExportPrefsHelper.setLastExportUri(context, exportUri);

    assertEquals(importUri, ExportPrefsHelper.getLastImportUri(context));
    assertEquals(exportUri, ExportPrefsHelper.getLastExportUri(context));

    // Update one, check other
    ExportPrefsHelper.setLastImportUri(context, "content://new/import");
    assertEquals("content://new/import", ExportPrefsHelper.getLastImportUri(context));
    assertEquals(exportUri, ExportPrefsHelper.getLastExportUri(context));
  }

  @Test
  public void testShareAttachOnlyPersistence() {
    // Default is false
    assertFalse(ExportPrefsHelper.isShareAttachOnly(context));

    // Set and retrieve true
    ExportPrefsHelper.setShareAttachOnly(context, true);
    assertTrue(ExportPrefsHelper.isShareAttachOnly(context));

    // Set and retrieve false
    ExportPrefsHelper.setShareAttachOnly(context, false);
    assertFalse(ExportPrefsHelper.isShareAttachOnly(context));
  }
}
