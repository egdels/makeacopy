package de.schliweb.makeacopy.ui.export.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The session's bookkeeping for "OCR in the background": a page is pending from the request until
 * its result reached the session, which is what an export waits for.
 */
public class ExportSessionBackgroundOcrTest {

  private ExportSessionViewModel vm;

  @Before
  public void setUp() {
    ArchTaskExecutor.getInstance()
        .setDelegate(
            new TaskExecutor() {
              @Override
              public void executeOnDiskIO(Runnable runnable) {
                runnable.run();
              }

              @Override
              public void postToMainThread(Runnable runnable) {
                runnable.run();
              }

              @Override
              public boolean isMainThread() {
                return true;
              }
            });
    vm = new ExportSessionViewModel();
  }

  @After
  public void tearDown() {
    ArchTaskExecutor.getInstance().setDelegate(null);
  }

  @Test
  public void aRequestedPageIsPendingBeforeItsJobExists() {
    vm.requestBackgroundOcr("p1");
    assertTrue(vm.isBackgroundOcrPending("p1"));
    // Not queued yet: "queued and unknown to the runner" must not read as finished
    assertTrue(vm.backgroundOcrQueuedSnapshot().isEmpty());
  }

  @Test
  public void aQueuedPageStaysPendingUntilDone() {
    vm.requestBackgroundOcr("p1");
    vm.markBackgroundOcrQueued("p1");
    assertTrue(vm.isBackgroundOcrPending("p1"));
    assertEquals(Set.of("p1"), vm.backgroundOcrQueuedSnapshot());

    vm.backgroundOcrDone("p1");
    assertFalse(vm.isBackgroundOcrPending("p1"));
    assertTrue(vm.backgroundOcrQueuedSnapshot().isEmpty());
  }

  @Test
  public void aPageThatCouldNotBeWrittenIsNoLongerPending() {
    vm.requestBackgroundOcr("p1");
    vm.backgroundOcrDone("p1");
    assertFalse(vm.isBackgroundOcrPending("p1"));
  }

  @Test
  public void pagesAreTrackedIndependently() {
    vm.requestBackgroundOcr("p1");
    vm.requestBackgroundOcr("p2");
    vm.markBackgroundOcrQueued("p2");
    vm.backgroundOcrDone("p2");
    assertTrue(vm.isBackgroundOcrPending("p1"));
    assertFalse(vm.isBackgroundOcrPending("p2"));
  }

  @Test
  public void unknownAndNullIdsAreNotPending() {
    assertFalse(vm.isBackgroundOcrPending("nope"));
    assertFalse(vm.isBackgroundOcrPending(null));
    vm.requestBackgroundOcr(null);
    vm.markBackgroundOcrQueued(null);
    vm.backgroundOcrDone(null);
    assertTrue(vm.backgroundOcrQueuedSnapshot().isEmpty());
  }

  @Test
  public void theSnapshotIsACopy() {
    vm.requestBackgroundOcr("p1");
    vm.markBackgroundOcrQueued("p1");
    Set<String> snapshot = vm.backgroundOcrQueuedSnapshot();
    vm.backgroundOcrDone("p1");
    assertEquals(Set.of("p1"), snapshot);
  }
}
