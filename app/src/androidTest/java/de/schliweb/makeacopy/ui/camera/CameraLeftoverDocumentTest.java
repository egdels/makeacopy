package de.schliweb.makeacopy.ui.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.Manifest;
import android.content.Context;
import android.graphics.Bitmap;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.assertion.ViewAssertions;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.data.DocumentSession;
import de.schliweb.makeacopy.data.DocumentSessionRepository;
import de.schliweb.makeacopy.data.RegistryCleaner;
import de.schliweb.makeacopy.testutil.HiltFragmentScenario;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import de.schliweb.makeacopy.utils.export.ScanPersister;
import java.util.Collections;
import java.util.UUID;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * An unfinished document left on the device (earlier run, manual use of the emulator) must not
 * decide whether camera UI tests pass: without a reset, the "resume document" dialog covers the
 * camera screen of the first camera test in the process.
 */
@RunWith(AndroidJUnit4.class)
public class CameraLeftoverDocumentTest {

  @Rule public GrantPermissionRule camPerm = GrantPermissionRule.grant(Manifest.permission.CAMERA);

  private String documentId;
  private String pageId;

  @After
  public void removeLeftoverDocument() {
    Context ctx = ApplicationProvider.getApplicationContext();
    if (documentId != null) DocumentSessionRepository.get(ctx).delete(documentId);
    if (pageId != null) RegistryCleaner.removeEntryAndFiles(ctx, pageId);
  }

  @Test
  public void cameraScreen_isNotCoveredByResumeDialog_whenADocumentWasLeftBehind()
      throws Exception {
    Context ctx = ApplicationProvider.getApplicationContext();
    DocumentSessionRepository repo = DocumentSessionRepository.get(ctx);
    Bitmap bmp = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888);
    bmp.eraseColor(0xFF336699);
    CompletedScan page =
        ScanPersister.persist(
            ctx,
            new CompletedScan(
                UUID.randomUUID().toString(),
                null,
                0,
                null,
                null,
                null,
                System.currentTimeMillis(),
                bmp.getWidth(),
                bmp.getHeight(),
                bmp,
                2,
                "baked"),
            null,
            null);
    pageId = page.id();
    DocumentSession leftover = repo.createSession(Collections.singletonList(pageId));
    documentId = leftover.documentId();
    assertEquals(
        "precondition: a resumable document exists", documentId, repo.getActiveDocumentId());

    HiltFragmentScenario.launchInHiltContainer(
        CameraFragment.class, null, R.style.Theme_MakeACopy, Lifecycle.State.RESUMED);

    Espresso.onView(ViewMatchers.withId(R.id.button_camera_options))
        .check(ViewAssertions.matches(ViewMatchers.isDisplayed()));
    assertNull("the leftover document is no longer active", repo.getActiveDocumentId());
    assertNotNull("but it is kept, not deleted", repo.findById(documentId));
  }
}
