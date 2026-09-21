package de.schliweb.makeacopy.utils.export;

import static org.junit.Assert.*;

import org.junit.Test;

/** JVM-only tests for the crop parameter document that {@link CropSourceStore} keeps per page. */
public class CropSourceStoreJsonTest {

  private static final float[] CORNERS = {12.5f, 30f, 2990.25f, 41f, 3001f, 3980.75f, 8f, 3975f};

  @Test
  public void roundTrip_keepsCornersAndRotation() {
    String json = CropSourceStore.toJson(CORNERS, 90);
    assertNotNull(json);
    assertArrayEquals(CORNERS, CropSourceStore.cornersFromJson(json), 0f);
    assertEquals(90, CropSourceStore.rotationFromJson(json));
  }

  @Test
  public void rotation_isNormalized() {
    assertEquals(270, CropSourceStore.rotationFromJson(CropSourceStore.toJson(CORNERS, -90)));
    assertEquals(0, CropSourceStore.rotationFromJson(CropSourceStore.toJson(CORNERS, 360)));
  }

  @Test
  public void toJson_rejectsIncompleteOrNonFiniteCorners() {
    assertNull(CropSourceStore.toJson(null, 0));
    assertNull(CropSourceStore.toJson(new float[6], 0));
    float[] nan = CORNERS.clone();
    nan[3] = Float.NaN;
    assertNull(CropSourceStore.toJson(nan, 0));
  }

  @Test
  public void cornersFromJson_rejectsBrokenDocuments() {
    assertNull(CropSourceStore.cornersFromJson(""));
    assertNull(CropSourceStore.cornersFromJson("not json"));
    assertNull(CropSourceStore.cornersFromJson("{}"));
    assertNull(CropSourceStore.cornersFromJson("{\"corners\":[[1,2],[3,4],[5,6]]}"));
    assertNull(CropSourceStore.cornersFromJson("{\"corners\":[[1,2],[3,4],[5,6],[7]]}"));
    assertNull(CropSourceStore.cornersFromJson("{\"corners\":[[1,2],[3,4],[5,6],[\"a\",8]]}"));
  }

  @Test
  public void rotationFromJson_defaultsToZero() {
    assertEquals(0, CropSourceStore.rotationFromJson("{\"corners\":[]}"));
    assertEquals(0, CropSourceStore.rotationFromJson("broken"));
  }
}
