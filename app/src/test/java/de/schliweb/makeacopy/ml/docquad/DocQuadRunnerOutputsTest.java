package de.schliweb.makeacopy.ml.docquad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class DocQuadRunnerOutputsTest {

  @Test
  public void constructor_setsFields() {
    float[][][][] mask = new float[1][1][64][64];
    float[][][][] corners = new float[1][4][64][64];
    DocQuadRunner.Outputs out = new DocQuadRunner.Outputs(mask, corners);
    assertSame(mask, out.maskLogits());
    assertSame(corners, out.cornerHeatmaps());
  }

  @Test
  public void constructor_nullFields_allowed() {
    DocQuadRunner.Outputs out = new DocQuadRunner.Outputs(null, null);
    assertNull(out.maskLogits());
    assertNull(out.cornerHeatmaps());
  }

  @Test
  public void record_equality() {
    float[][][][] mask = new float[1][1][64][64];
    float[][][][] corners = new float[1][4][64][64];
    DocQuadRunner.Outputs a = new DocQuadRunner.Outputs(mask, corners);
    DocQuadRunner.Outputs b = new DocQuadRunner.Outputs(mask, corners);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void constants_correctValues() {
    assertEquals(256, DocQuadRunner.IN_H);
    assertEquals(256, DocQuadRunner.IN_W);
    assertEquals(64, DocQuadRunner.OUT_H);
    assertEquals(64, DocQuadRunner.OUT_W);
  }
}
