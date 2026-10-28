package de.schliweb.makeacopy.testutil;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

/**
 * PDFBox for reading exported PDFs back. The app writes PDFs without it, so PDFBox and its
 * resources are only in the test APK: it has to be set up with the test APK's context.
 */
public final class PdfBoxTesting {
  private PdfBoxTesting() {}

  public static void init() {
    Context test = InstrumentationRegistry.getInstrumentation().getContext();
    // PDFBoxResourceLoader keeps the application context, which the test context does not have.
    PDFBoxResourceLoader.init(
        new ContextWrapper(test) {
          @Override
          public Context getApplicationContext() {
            return this;
          }
        });
  }
}
