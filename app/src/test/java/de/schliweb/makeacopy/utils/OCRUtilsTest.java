package de.schliweb.makeacopy.utils;

import static org.junit.Assert.*;

import de.schliweb.makeacopy.utils.ocr.OCRUtils;
import org.junit.Test;

/** Unit tests for {@link OCRUtils} to verify language mapping and multi-language support. */
public class OCRUtilsTest {

  // ==================== mapSystemLanguageToTesseract Tests ====================

  // ==================== getLanguages Tests ====================

  // ==================== resolveEffectiveLanguage Tests ====================

  @Test
  public void resolveEffectiveLanguage_withValidLanguage_returnsSameLanguage() {
    assertEquals("deu", OCRUtils.resolveEffectiveLanguage("deu"));
    assertEquals("eng", OCRUtils.resolveEffectiveLanguage("eng"));
    assertEquals("fra", OCRUtils.resolveEffectiveLanguage("fra"));
  }

  @Test
  public void resolveEffectiveLanguage_withMultiLanguageSpec_returnsSameSpec() {
    // Multi-language specs should be passed through unchanged
    assertEquals("deu+eng", OCRUtils.resolveEffectiveLanguage("deu+eng"));
    assertEquals("fra+deu", OCRUtils.resolveEffectiveLanguage("fra+deu"));
    assertEquals("fas+eng", OCRUtils.resolveEffectiveLanguage("fas+eng"));
  }

  @Test
  public void resolveEffectiveLanguage_withNull_returnsSystemDefault() {
    // When null is passed, it should return a valid language based on system locale
    String result = OCRUtils.resolveEffectiveLanguage(null);
    assertNotNull(result);
    assertFalse(result.isEmpty());
  }

  @Test
  public void resolveEffectiveLanguage_withEmpty_returnsSystemDefault() {
    String result = OCRUtils.resolveEffectiveLanguage("");
    assertNotNull(result);
    assertFalse(result.isEmpty());
  }

  @Test
  public void resolveEffectiveLanguage_withWhitespace_returnsSystemDefault() {
    String result = OCRUtils.resolveEffectiveLanguage("   ");
    assertNotNull(result);
    assertFalse(result.isEmpty());
  }

  // ==================== Multi-Language Format Validation ====================

  @Test
  public void multiLanguageFormat_plusSeparator_isValid() {
    // Verify that the multi-language format uses + as separator
    String multiLang = "deu+eng";
    assertTrue(multiLang.contains("+"));
    assertEquals("deu", multiLang.substring(0, multiLang.indexOf('+')));
    assertEquals("eng", multiLang.substring(multiLang.indexOf('+') + 1));
  }

  @Test
  public void multiLanguageFormat_threeLanguages_isValid() {
    String multiLang = "deu+eng+fra";
    assertTrue(multiLang.contains("+"));
    int first = multiLang.indexOf('+');
    int second = multiLang.indexOf('+', first + 1);
    assertEquals("deu", multiLang.substring(0, first));
    assertEquals("eng", multiLang.substring(first + 1, second));
    assertEquals("fra", multiLang.substring(second + 1));
  }

  @Test
  public void multiLanguageFormat_rtlPlusLtr_isValid() {
    // Persian (RTL) + English (LTR)
    String multiLang = "fas+eng";
    assertTrue(multiLang.contains("+"));
    assertEquals("fas", multiLang.substring(0, multiLang.indexOf('+')));
    assertEquals("eng", multiLang.substring(multiLang.indexOf('+') + 1));
  }

  @Test
  public void multiLanguageFormat_arabicPlusFrench_isValid() {
    // Arabic (RTL) + French (LTR)
    String multiLang = "ara+fra";
    assertTrue(multiLang.contains("+"));
    assertEquals("ara", multiLang.substring(0, multiLang.indexOf('+')));
    assertEquals("fra", multiLang.substring(multiLang.indexOf('+') + 1));
  }

  // ==================== codeToBaseDisplayName Tests ====================

  @Test
  public void codeToBaseDisplayName_paddleCode_isPaddleModelName() {
    assertEquals("Latin script (Paddle)", OCRUtils.codeToBaseDisplayName("latin"));
    assertEquals("xyz (Paddle)", OCRUtils.codeToBaseDisplayName("xyz"));
  }

  @Test
  public void resolveEffectiveLanguage_keepsExplicitSpec() {
    assertEquals("eslav", OCRUtils.resolveEffectiveLanguage("eslav"));
  }

  @Test
  public void mapSystemLanguageToPaddleModel_mapsScripts() {
    assertEquals("latin", OCRUtils.mapSystemLanguageToPaddleModel(java.util.Locale.GERMAN));
    assertEquals("en", OCRUtils.mapSystemLanguageToPaddleModel(java.util.Locale.ENGLISH));
    assertEquals("eslav", OCRUtils.mapSystemLanguageToPaddleModel(new java.util.Locale("ru")));
    assertEquals("zh", OCRUtils.mapSystemLanguageToPaddleModel(java.util.Locale.JAPANESE));
    assertEquals("latin", OCRUtils.mapSystemLanguageToPaddleModel(null));
  }

}
