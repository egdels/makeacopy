package de.schliweb.makeacopy.data.library;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that the default "Completed Scans" collection is identified by its fixed ID, so changing
 * the language no longer creates a second one, and that data from before the fixed ID is adopted.
 */
public class DefaultCollectionResolutionTest {

  private static final String ID = CollectionsRepository.DEFAULT_COLLECTION_ID;
  private static final String EN = "Completed scans";
  private static final String DE = "Abgeschlossene Scans";
  private static final List<String> ALL_NAMES = Arrays.asList(EN, DE, "Digitalizações concluídas");

  private static final String COMPLETED_META = "{\"type\":\"CompletedScanEntry\"}";

  private final Map<String, CollectionEntity> collections = new LinkedHashMap<>();
  private final List<ScanCollectionCrossRef> joins = new ArrayList<>();
  private final Map<String, ScanEntity> scans = new LinkedHashMap<>();

  private DefaultCollectionsRepository repo;

  @Before
  public void setUp() {
    repo = new DefaultCollectionsRepository(new FakeCollectionsDao(), new FakeJoinDao(), new FakeScansDao());
  }

  @Test
  public void freshInstall_createsDefaultWithFixedId() {
    CollectionEntity def = repo.resolveDefaultCollection(EN, () -> ALL_NAMES);

    assertEquals(ID, def.id);
    assertEquals(EN, def.name);
    assertEquals(1, collections.size());
  }

  @Test
  public void languageChange_keepsSingleDefaultAndTranslatesItsName() {
    repo.resolveDefaultCollection(EN, () -> ALL_NAMES);
    addScan("s1", COMPLETED_META, ID);

    CollectionEntity def = repo.resolveDefaultCollection(DE, () -> ALL_NAMES);

    assertEquals(ID, def.id);
    assertEquals(DE, def.name);
    assertEquals(1, collections.size());
    assertEquals(List.of("s1"), itemsOf(ID));
  }

  @Test
  public void languageChange_keepsNameIfAUserCollectionAlreadyUsesIt() {
    repo.resolveDefaultCollection(EN, () -> ALL_NAMES);
    addCollection("user", "Meine Sammlung");
    collections.get("user").name = DE; // cannot happen via the UI, but must not produce two equal names

    CollectionEntity def = repo.resolveDefaultCollection(DE, () -> ALL_NAMES);

    assertEquals(EN, def.name);
  }

  @Test
  public void existingInstall_adoptsNameBasedDefaultAndKeepsItsItems() {
    addCollection("uuid-1", EN);
    addScan("s1", COMPLETED_META, "uuid-1");
    addScan("s2", COMPLETED_META, "uuid-1");

    CollectionEntity def = repo.resolveDefaultCollection(EN, () -> ALL_NAMES);

    assertEquals(ID, def.id);
    assertEquals(1, collections.size());
    assertEquals(List.of("s1", "s2"), itemsOf(ID));
  }

  @Test
  public void existingInstall_adoptsDefaultCreatedInAnotherLanguage() {
    addCollection("uuid-1", DE); // created while the app was German
    addScan("s1", COMPLETED_META, "uuid-1");

    CollectionEntity def = repo.resolveDefaultCollection(EN, () -> ALL_NAMES);

    assertEquals(ID, def.id);
    assertEquals(EN, def.name); // shown in the current language
    assertEquals(1, collections.size());
    assertEquals(List.of("s1"), itemsOf(ID));
  }

  @Test
  public void existingDuplicates_areMergedIntoTheDefault() {
    addCollection("uuid-de", DE); // original default
    addCollection("uuid-en", EN); // duplicate created after a language change
    addScan("s1", COMPLETED_META, "uuid-de", "uuid-en");
    addScan("s2", COMPLETED_META, "uuid-de");
    addScan("s3", COMPLETED_META, "uuid-en");

    CollectionEntity def = repo.resolveDefaultCollection(EN, () -> ALL_NAMES);

    assertEquals(ID, def.id);
    assertEquals(1, collections.size());
    assertEquals(List.of("s1", "s2", "s3"), itemsOf(ID));
  }

  @Test
  public void userCollectionWithADefaultName_isNotMerged() {
    addCollection("uuid-en", EN);
    addCollection("uuid-user", DE); // same name as the German default, but holds a finished document
    addScan("doc", "{\"type\":\"ExportedDocument\"}", "uuid-user");

    repo.resolveDefaultCollection(EN, () -> ALL_NAMES);

    assertTrue(collections.containsKey("uuid-user"));
    assertEquals(List.of("doc"), itemsOf("uuid-user"));
    assertEquals(List.of(), itemsOf(ID));
  }

  @Test
  public void afterAdoption_translatedNamesAreNotEvaluatedAgain() {
    repo.resolveDefaultCollection(EN, () -> ALL_NAMES);

    CollectionEntity def =
        repo.resolveDefaultCollection(
            EN,
            () -> {
              throw new AssertionError("names must only be needed for the one-time adoption");
            });

    assertEquals(ID, def.id);
  }

  // --- helpers and in-memory fakes ---

  private void addCollection(String id, String name) {
    collections.put(id, new CollectionEntity(id, name, collections.size(), collections.size()));
  }

  private void addScan(String scanId, String meta, String... collectionIds) {
    scans.put(scanId, new ScanEntity(scanId, scanId, 0L, 1, null, null, meta));
    for (String c : collectionIds) joins.add(new ScanCollectionCrossRef(scanId, c, 0L));
  }

  private List<String> itemsOf(String collectionId) {
    return joins.stream()
        .filter(j -> j.collectionId.equals(collectionId))
        .map(j -> j.scanId)
        .sorted()
        .collect(Collectors.toList());
  }

  private boolean hasJoin(String scanId, String collectionId) {
    return joins.stream()
        .anyMatch(j -> j.scanId.equals(scanId) && j.collectionId.equals(collectionId));
  }

  private class FakeCollectionsDao implements CollectionsDao {
    @Override
    public void insert(CollectionEntity c) {
      if (collections.containsKey(c.id)) throw new IllegalStateException("duplicate id " + c.id);
      collections.put(c.id, c);
    }

    @Override
    public void update(CollectionEntity c) {
      collections.put(c.id, c);
    }

    @Override
    public List<CollectionEntity> getAll() {
      return new ArrayList<>(collections.values());
    }

    @Override
    public CollectionEntity getById(String id) {
      return collections.get(id);
    }

    @Override
    public CollectionEntity getByName(String name) {
      return collections.values().stream().filter(c -> c.name.equals(name)).findFirst().orElse(null);
    }

    @Override
    public void deleteById(String id) {
      collections.remove(id);
    }

    @Override
    public int countItems(String collectionId) {
      return itemsOf(collectionId).size();
    }

    @Override
    public void updateId(String oldId, String newId) {
      CollectionEntity c = collections.remove(oldId);
      c.id = newId;
      collections.put(newId, c);
    }

    @Override
    public void moveItems(String oldId, String newId) {
      // UPDATE OR IGNORE: rows that would duplicate an existing (scanId, newId) pair stay behind
      for (ScanCollectionCrossRef j : new ArrayList<>(joins)) {
        if (j.collectionId.equals(oldId) && !hasJoin(j.scanId, newId)) j.collectionId = newId;
      }
    }

    @Override
    public void removeAllItems(String collectionId) {
      joins.removeIf(j -> j.collectionId.equals(collectionId));
    }
  }

  private class FakeJoinDao implements ScanCollectionJoinDao {
    @Override
    public void insert(ScanCollectionCrossRef join) {
      if (!hasJoin(join.scanId, join.collectionId)) joins.add(join);
    }

    @Override
    public void remove(String scanId, String collectionId) {
      joins.removeIf(j -> j.scanId.equals(scanId) && j.collectionId.equals(collectionId));
    }

    @Override
    public void removeAllForScan(String scanId) {
      joins.removeIf(j -> j.scanId.equals(scanId));
    }

    @Override
    public List<String> getScanIdsForCollection(String collectionId) {
      return itemsOf(collectionId);
    }

    @Override
    public List<String> getCollectionIdsForScan(String scanId) {
      return joins.stream()
          .filter(j -> j.scanId.equals(scanId))
          .map(j -> j.collectionId)
          .collect(Collectors.toList());
    }
  }

  private class FakeScansDao implements ScansDao {
    @Override
    public void insert(ScanEntity scan) {
      scans.put(scan.id, scan);
    }

    @Override
    public void update(ScanEntity scan) {
      scans.put(scan.id, scan);
    }

    @Override
    public List<ScanEntity> getAll() {
      return new ArrayList<>(scans.values());
    }

    @Override
    public ScanEntity getById(String id) {
      return scans.get(id);
    }

    @Override
    public void deleteById(String id) {
      scans.remove(id);
    }

    @Override
    public List<ScanEntity> getAllByCollection(String collectionId) {
      return itemsOf(collectionId).stream().map(scans::get).collect(Collectors.toList());
    }
  }
}
