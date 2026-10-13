package de.schliweb.makeacopy.ui.ocr.review.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Tests how the review text layer fits a word into its box width. */
public class OcrTextLayerViewFitTest {

  private static final float EPS = 1e-4f;

  @Test
  public void keepsSizeWhenTextFits() {
    assertEquals(40f, OcrTextLayerView.fitTextSize(40f, 100f, 120f, 8f), EPS);
    assertEquals(40f, OcrTextLayerView.fitTextSize(40f, 120f, 120f, 8f), EPS);
  }

  @Test
  public void shrinksSizeInProportionWhenTextIsTooWide() {
    // 150 px wide at size 40, 120 px available -> 40 * 120 / 150 = 32
    assertEquals(32f, OcrTextLayerView.fitTextSize(40f, 150f, 120f, 8f), EPS);
  }

  @Test
  public void neverGoesBelowMinimumSize() {
    assertEquals(8f, OcrTextLayerView.fitTextSize(40f, 1000f, 50f, 8f), EPS);
  }

  @Test
  public void keepsSizeWhenWidthIsUnknownOrBoxHasNoRoom() {
    assertEquals(40f, OcrTextLayerView.fitTextSize(40f, 0f, 120f, 8f), EPS);
    assertEquals(40f, OcrTextLayerView.fitTextSize(40f, 150f, 0f, 8f), EPS);
    assertEquals(40f, OcrTextLayerView.fitTextSize(40f, 150f, -3f, 8f), EPS);
  }
}
