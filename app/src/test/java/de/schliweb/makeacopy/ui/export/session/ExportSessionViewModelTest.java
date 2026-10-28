package de.schliweb.makeacopy.ui.export.session;

import static org.junit.Assert.*;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the {@code ExportSessionViewModel} class. This test class verifies the
 * functionality of the {@code ExportSessionViewModel} through various scenarios including adding,
 * removing, moving, and checking for items.
 */
public class ExportSessionViewModelTest {

  /**
   * Represents an instance of {@link ExportSessionViewModel} used in the test class. This variable
   * acts as the test subject across a range of unit tests to verify the functionality of {@link
   * ExportSessionViewModel} methods, such as managing a collection of completed scans and
   * performing operations like addition, removal, and reordering of scans.
   */
  private ExportSessionViewModel vm;

  /**
   * Creates a new instance of the {@link CompletedScan} class with the specified suffix appended to
   * the ID.
   *
   * @param idSuffix The suffix to append to the base ID. This is used to generate a unique
   *     identifier for the {@link CompletedScan}.
   * @return A new {@link CompletedScan} instance with the generated ID and default values for other
   *     properties.
   */
  private CompletedScan make(String idSuffix) {
    return new CompletedScan(
        "id-" + idSuffix,
        null,
        0,
        null,
        null,
        null,
        System.currentTimeMillis(),
        0,
        0,
        null,
        1,
        "baked");
  }

  /**
   * Sets up the test environment by configuring the ArchTaskExecutor to run synchronously in the
   * JVM testing environment. This ensures that LiveData updates are executed immediately, enabling
   * accurate and deterministic testing behavior. Additionally, an instance of
   * ExportSessionViewModel is initialized for use in test cases.
   *
   * <p>This method is annotated with @Before, meaning it is automatically executed before each test
   * method in the test class.
   */
  @Before
  public void setUp() {
    // Ensure LiveData posts run synchronously in JVM tests
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

  /**
   * Cleans up the state after each test method is executed by resetting the delegate of the
   * ArchTaskExecutor to null. This ensures that the ArchTaskExecutor does not retain any state or
   * configuration changes made during a test, preventing potential interference with subsequent
   * tests.
   *
   * <p>This method is annotated with @After, meaning it is automatically executed after each test
   * method in the test class.
   */
  @After
  public void tearDown() {
    ArchTaskExecutor.getInstance().setDelegate(null);
  }

  /**
   * Tests the behavior of the `setInitial` method in the `ExportSessionViewModel` class.
   *
   * <p>This test verifies the following: - The initial state of the `pages` LiveData is not null
   * and contains an empty list. - After invoking `setInitial` with a `CompletedScan` object, the
   * `pages` LiveData is updated to contain a single element. - The single element in the `pages`
   * list corresponds to the `CompletedScan` object provided to the `setInitial` method, and its ID
   * matches the expected value.
   */
  @Test
  public void testSetInitial() {
    assertNotNull(vm.getPages().getValue());
    assertEquals(0, vm.getPages().getValue().size());
    CompletedScan a = make("A");
    vm.setInitial(a);
    List<CompletedScan> pages = vm.getPages().getValue();
    assertNotNull(pages);
    assertEquals(1, pages.size());
    assertEquals("id-A", pages.get(0).id());
  }

  /**
   * Validates the functionality of adding `CompletedScan` objects to the pages list and verifying
   * their presence via the `contains` method in the `ExportSessionViewModel`.
   *
   * <p>This test specifically ensures the following: - Adding a `CompletedScan` updates the `pages`
   * LiveData with the correct number of entries. - Each added `CompletedScan` can be identified by
   * its unique ID using the `contains` method. - The `contains` method correctly returns `false`
   * for IDs not present in the collection.
   *
   * <p>Steps performed: - Create two `CompletedScan` objects. - Set an initial scan object using
   * `setInitial`. - Add another scan to the collection using `add`. - Verify the size of the
   * `pages` list reflects the additions. - Confirm the presence of the added scans using their
   * unique IDs. - Confirm that querying an ID not present in the collection returns `false`.
   */
  @Test
  public void testAddAndContains() {
    CompletedScan a = make("A");
    CompletedScan b = make("B");
    vm.setInitial(a);
    vm.add(b);
    List<CompletedScan> pages = vm.getPages().getValue();
    assertEquals(2, pages.size());
    assertTrue(vm.contains("id-A"));
    assertTrue(vm.contains("id-B"));
    assertFalse(vm.contains("id-C"));
  }

  /**
   * Tests the `add` method of the `ExportSessionViewModel` to ensure it correctly deduplicates
   * entries based on their unique identifier (`id` field). The test verifies that adding a
   * `CompletedScan` object with the same `id` as an existing entry in the collection does not
   * increase the size of the collection.
   *
   * <p>Key behaviors validated by this test: - When a `CompletedScan` with a unique `id` is added,
   * it is included in the `pages` list. - When a `CompletedScan` with a duplicate `id` is added,
   * the `pages` list remains unchanged.
   *
   * <p>Test setup and sequence: - A `CompletedScan` instance (`a1`) is created using the `make`
   * method with an `id` suffix. - Another `CompletedScan` instance (`a2`) is manually created with
   * the same `id` as `a1`. - The `setInitial` method is called with `a1` to populate the initial
   * state of the `pages` LiveData. - The `add` method is called with `a2`, attempting to add a
   * duplicate entry. - The size of the `pages` list is asserted to ensure it remains 1, confirming
   * no duplication occurred.
   */
  @Test
  public void testAdd_DeduplicatesById() {
    CompletedScan a1 = make("X");
    CompletedScan a2 =
        new CompletedScan(
            "id-X", null, 0, null, null, null, System.currentTimeMillis(), 0, 0, null, 1, "baked");
    vm.setInitial(a1);
    vm.add(a2);
    assertEquals(1, vm.getPages().getValue().size());
  }

  /**
   * Tests the behavior of the `addAll` method in the `ExportSessionViewModel`, specifically
   * ensuring that it correctly handles duplicate entries based on their unique identifier (`id`
   * field).
   *
   * <p>This test validates the following: - When a list of `CompletedScan` objects is added, only
   * unique scans are included in the `pages` list. - Scans with duplicate `id` values are not added
   * multiple times, maintaining a distinct collection.
   *
   * <p>Test setup and sequence: - Create two `CompletedScan` objects (`a` and `b`) with unique `id`
   * values. - Create another `CompletedScan` object (`dupA`) with the same `id` as `a`. - Use the
   * `setInitial` method to initialize the `pages` list with the first scan (`a`). - Invoke the
   * `addAll` method with a list containing `b` and `dupA`. - Assert that the `pages` list contains
   * exactly two elements, confirming that duplicates are excluded. - Verify that the order and IDs
   * of the elements in the `pages` list match the expected values.
   */
  @Test
  public void testAddAll_Deduplicates() {
    CompletedScan a = make("A");
    CompletedScan b = make("B");
    CompletedScan dupA =
        new CompletedScan(
            "id-A", null, 0, null, null, null, System.currentTimeMillis(), 0, 0, null, 1, "baked");
    vm.setInitial(a);
    vm.addAll(Arrays.asList(b, dupA));
    List<CompletedScan> pages = vm.getPages().getValue();
    assertEquals(2, pages.size());
    assertEquals("id-A", pages.get(0).id());
    assertEquals("id-B", pages.get(1).id());
  }

  /**
   * Tests the behavior of the `removeAt` method in the `ExportSessionViewModel` class, ensuring the
   * proper removal of items based on their index and handling of out-of-bounds scenarios.
   *
   * <p>This test verifies the following: - Removal of an item at a valid index updates the `pages`
   * LiveData list correctly. - The order and identifiers of the remaining items in the list are
   * preserved. - Attempts to remove an item at an out-of-bounds index do not throw exceptions or
   * modify the list.
   *
   * <p>Test setup and sequence: - Create three `CompletedScan` objects (`a`, `b`, and `c`) with
   * distinct IDs. - Initialize the `pages` list with `a`, `b`, and `c` using the `setInitial` and
   * `addAll` methods. - Assert the initial size of the `pages` list to confirm all scans are added
   * correctly. - Call `removeAt` with a valid index (1), verify the size of the list is reduced,
   * and check that the item at the index is removed. - Verify the remaining items in the list
   * retain their correct order and IDs. - Call `removeAt` with an out-of-bounds index, ensure no
   * exceptions are thrown, and assert the size of the list remains unchanged.
   */
  @Test
  public void testRemoveAt() {
    CompletedScan a = make("A");
    CompletedScan b = make("B");
    CompletedScan c = make("C");
    vm.setInitial(a);
    vm.addAll(Arrays.asList(b, c));
    assertEquals(3, vm.getPages().getValue().size());
    vm.removeAt(1); // remove B
    List<CompletedScan> pages = vm.getPages().getValue();
    assertEquals(2, pages.size());
    assertEquals("id-A", pages.get(0).id());
    assertEquals("id-C", pages.get(1).id());

    // out-of-bounds should be ignored
    vm.removeAt(5);
    assertEquals(2, vm.getPages().getValue().size());
  }

  /**
   * Tests the behavior of the `move` method in the `ExportSessionViewModel` class when rearranging
   * elements within the valid bounds of the `pages` LiveData list.
   *
   * <p>This test ensures the following: - The `move` method correctly repositions an element from
   * one index to another within the bounds of the list. - The updated order of the elements in the
   * `pages` list matches the expected result after the `move` operation. - The total size of the
   * list remains unchanged.
   *
   * <p>Test setup and sequence: - Create three `CompletedScan` objects (`a`, `b`, and `c`) with
   * unique IDs. - Set up the initial state of the `pages` list with these scans using the
   * `setInitial` and `addAll` methods. - Invoke the `move` method to reposition an element (`c`)
   * from its current index to a new index. - Retrieve and validate the updated `pages` list: -
   * Ensure the size of the list remains the same. - Verify that the new order of elements matches
   * the expected result after the move.
   */
  @Test
  public void testMoveWithinBounds() {
    CompletedScan a = make("A");
    CompletedScan b = make("B");
    CompletedScan c = make("C");
    vm.setInitial(a);
    vm.addAll(Arrays.asList(b, c));
    // A, B, C → move C to index 0 → C, A, B
    vm.move(2, 0);
    List<CompletedScan> pages = vm.getPages().getValue();
    assertEquals(3, pages.size());
    assertEquals("id-C", pages.get(0).id());
    assertEquals("id-A", pages.get(1).id());
    assertEquals("id-B", pages.get(2).id());
  }

  /**
   * Tests the behavior of the `move` method in the `ExportSessionViewModel` class when attempting
   * to move elements to or from out-of-bounds indices in the `pages` LiveData list.
   *
   * <p>This test validates the following: - Attempts to move an element from an out-of-bounds
   * source index do not modify the list. - Attempts to move an element to an out-of-bounds
   * destination index do not modify the list. - The size of the `pages` list remains unchanged. -
   * The order and identifiers of the elements in the `pages` list are preserved.
   *
   * <p>Test setup and sequence: - Create two `CompletedScan` objects (`a` and `b`) with unique IDs.
   * - Initialize the `pages` list with `a` and `b` using the `setInitial` and `add` methods. -
   * Invoke the `move` method with source and destination indices that are out of bounds. - Retrieve
   * and validate the `pages` list: - Ensure the size of the list remains unchanged. - Verify the
   * order and IDs of the elements match their original state.
   */
  @Test
  public void testMoveOutOfBoundsNoOp() {
    CompletedScan a = make("A");
    CompletedScan b = make("B");
    vm.setInitial(a);
    vm.add(b); // A, B
    vm.move(-1, 0); // ignore
    vm.move(0, 5); // ignore
    List<CompletedScan> pages = vm.getPages().getValue();
    assertEquals(2, pages.size());
    assertEquals("id-A", pages.get(0).id());
    assertEquals("id-B", pages.get(1).id());
  }

  @Test
  public void ocrStateReplaced_isTrackedPerPage() {
    assertFalse(vm.isOcrStateReplaced("id-a"));
    vm.markOcrStateReplaced("id-a");
    vm.markOcrStateReplaced(null);
    assertTrue(vm.isOcrStateReplaced("id-a"));
    assertFalse(vm.isOcrStateReplaced("id-b"));
    assertFalse(vm.isOcrStateReplaced(null));
  }
}
