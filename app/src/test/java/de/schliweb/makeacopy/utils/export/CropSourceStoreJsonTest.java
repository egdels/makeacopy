package de.schliweb.makeacopy.utils.export;

import static org.junit.Assert.*;

import de.schliweb.makeacopy.utils.image.DewarpState;
import org.junit.Test;

/** JVM-only tests for the crop parameter document that {@link CropSourceStore} keeps per page. */
public class CropSourceStoreJsonTest {

  private static final float[] CORNERS = {12.5f, 30f, 2990.25f, 41f, 3001f, 3980.75f, 8f, 3975f};

  private static final DewarpState DEWARP =
      new DewarpState(
          0.08, -0.05, 0.02, -0.01, 0.3, new double[] {0, 0.04, 0.08, 0.04, 0}, null);

  @Test
  public void roundTrip_keepsCornersAndRotation() {
    String json = CropSourceStore.toJson(CORNERS, 90, null);
    assertNotNull(json);
    assertArrayEquals(CORNERS, CropSourceStore.cornersFromJson(json), 0f);
    assertEquals(90, CropSourceStore.rotationFromJson(json));
    assertNull(CropSourceStore.dewarpFromJson(json));
  }

  @Test
  public void roundTrip_keepsDewarpState() {
    String json = CropSourceStore.toJson(CORNERS, 0, DEWARP);
    assertNotNull(json);
    DewarpState back = CropSourceStore.dewarpFromJson(json);
    assertNotNull(back);
    assertEquals(DEWARP.topOffsetFrac(), back.topOffsetFrac(), 0);
    assertEquals(DEWARP.bottomOffsetFrac(), back.bottomOffsetFrac(), 0);
    assertEquals(DEWARP.topTangentFrac(), back.topTangentFrac(), 0);
    assertEquals(DEWARP.bottomTangentFrac(), back.bottomTangentFrac(), 0);
    assertEquals(DEWARP.depth(), back.depth(), 0);
    assertArrayEquals(DEWARP.topProfile(), back.topProfile(), 0);
    assertNull(back.bottomProfile());
    // The dewarp block must not disturb the corners and rotation next to it
    assertArrayEquals(CORNERS, CropSourceStore.cornersFromJson(json), 0f);
    assertEquals(0, CropSourceStore.rotationFromJson(json));
  }

  @Test
  public void toJson_dropsNonFiniteDewarpStateButKeepsTheCrop() {
    DewarpState broken = new DewarpState(Double.NaN, 0, 0, 0, 0, null, null);
    String json = CropSourceStore.toJson(CORNERS, 0, broken);
    assertNotNull(json);
    assertArrayEquals(CORNERS, CropSourceStore.cornersFromJson(json), 0f);
    assertNull(CropSourceStore.dewarpFromJson(json));
  }

  @Test
  public void dewarpFromJson_toleratesLegacyAndBrokenDocuments() {
    // Version 1 documents (before issue #91 settings were stored) simply have no dewarp block
    assertNull(
        CropSourceStore.dewarpFromJson(
            "{\"version\":1,\"userRotationDeg\":0,\"corners\":[[1,2],[3,4],[5,6],[7,8]]}"));
    assertNull(CropSourceStore.dewarpFromJson("broken"));
    assertNull(CropSourceStore.dewarpFromJson("{\"dewarp\":\"x\"}"));
    assertNull(CropSourceStore.dewarpFromJson("{\"dewarp\":{}}"));
    assertNull(CropSourceStore.dewarpFromJson("{\"dewarp\":{\"topOffsetFrac\":\"a\"}}"));
    // Tangents, depth and profiles are optional: a minimal block is still a curved crop
    DewarpState minimal =
        CropSourceStore.dewarpFromJson("{\"dewarp\":{\"topOffsetFrac\":0.1,\"bottomOffsetFrac\":0}}");
    assertNotNull(minimal);
    assertEquals(0.1, minimal.topOffsetFrac(), 0);
    assertEquals(0, minimal.depth(), 0);
    assertNull(minimal.topProfile());
  }

  @Test
  public void rotation_isNormalized() {
    assertEquals(
        270, CropSourceStore.rotationFromJson(CropSourceStore.toJson(CORNERS, -90, null)));
    assertEquals(0, CropSourceStore.rotationFromJson(CropSourceStore.toJson(CORNERS, 360, null)));
  }

  @Test
  public void toJson_rejectsIncompleteOrNonFiniteCorners() {
    assertNull(CropSourceStore.toJson(null, 0, null));
    assertNull(CropSourceStore.toJson(new float[6], 0, null));
    float[] nan = CORNERS.clone();
    nan[3] = Float.NaN;
    assertNull(CropSourceStore.toJson(nan, 0, null));
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
