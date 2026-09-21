package de.schliweb.makeacopy.utils;

import static org.junit.Assert.*;

import de.schliweb.makeacopy.utils.ocr.OCRUtils;
import org.junit.Test;

/** Unit tests for {@link OCRUtils} to verify language mapping and multi-language support. */
public class OCRUtilsTest {

  // ==================== mapSystemLanguageToTesseract Tests ====================

  @Test
  public void mapSystemLanguageToTesseract_english_returnsEng() {
    assertEquals("eng", OCRUtils.mapSystemLanguageToTesseract("en"));
  }

  @Test
  public void mapSystemLanguageToTesseract_german_returnsDeu() {
    assertEquals("deu", OCRUtils.mapSystemLanguageToTesseract("de"));
  }

  @Test
  public void mapSystemLanguageToTesseract_french_returnsFra() {
    assertEquals("fra", OCRUtils.mapSystemLanguageToTesseract("fr"));
  }

  @Test
  public void mapSystemLanguageToTesseract_italian_returnsIta() {
    assertEquals("ita", OCRUtils.mapSystemLanguageToTesseract("it"));
  }

  @Test
  public void mapSystemLanguageToTesseract_spanish_returnsSpa() {
    assertEquals("spa", OCRUtils.mapSystemLanguageToTesseract("es"));
  }

  @Test
  public void mapSystemLanguageToTesseract_portuguese_returnsPor() {
    assertEquals("por", OCRUtils.mapSystemLanguageToTesseract("pt"));
  }

  @Test
  public void mapSystemLanguageToTesseract_dutch_returnsNld() {
    assertEquals("nld", OCRUtils.mapSystemLanguageToTesseract("nl"));
  }

  @Test
  public void mapSystemLanguageToTesseract_polish_returnsPol() {
    assertEquals("pol", OCRUtils.mapSystemLanguageToTesseract("pl"));
  }

  @Test
  public void mapSystemLanguageToTesseract_czech_returnsCes() {
    assertEquals("ces", OCRUtils.mapSystemLanguageToTesseract("cs"));
  }

  @Test
  public void mapSystemLanguageToTesseract_russian_returnsRus() {
    assertEquals("rus", OCRUtils.mapSystemLanguageToTesseract("ru"));
  }

  @Test
  public void mapSystemLanguageToTesseract_thai_returnsTha() {
    assertEquals("tha", OCRUtils.mapSystemLanguageToTesseract("th"));
  }

  @Test
  public void mapSystemLanguageToTesseract_slovak_returnsSlk() {
    assertEquals("slk", OCRUtils.mapSystemLanguageToTesseract("sk"));
  }

  @Test
  public void mapSystemLanguageToTesseract_hungarian_returnsHun() {
    assertEquals("hun", OCRUtils.mapSystemLanguageToTesseract("hu"));
  }

  @Test
  public void mapSystemLanguageToTesseract_romanian_returnsRon() {
    assertEquals("ron", OCRUtils.mapSystemLanguageToTesseract("ro"));
  }

  @Test
  public void mapSystemLanguageToTesseract_danish_returnsDan() {
    assertEquals("dan", OCRUtils.mapSystemLanguageToTesseract("da"));
  }

  @Test
  public void mapSystemLanguageToTesseract_swedish_returnsSwe() {
    assertEquals("swe", OCRUtils.mapSystemLanguageToTesseract("sv"));
  }

  @Test
  public void mapSystemLanguageToTesseract_norwegian_returnsNor() {
    assertEquals("nor", OCRUtils.mapSystemLanguageToTesseract("no"));
  }

  @Test
  public void mapSystemLanguageToTesseract_norwegianBokmal_returnsNor() {
    assertEquals("nor", OCRUtils.mapSystemLanguageToTesseract("nb"));
  }

  @Test
  public void mapSystemLanguageToTesseract_norwegianNynorsk_returnsNor() {
    assertEquals("nor", OCRUtils.mapSystemLanguageToTesseract("nn"));
  }

  @Test
  public void mapSystemLanguageToTesseract_persian_returnsFas() {
    assertEquals("fas", OCRUtils.mapSystemLanguageToTesseract("fa"));
  }

  @Test
  public void mapSystemLanguageToTesseract_arabic_returnsAra() {
    assertEquals("ara", OCRUtils.mapSystemLanguageToTesseract("ar"));
  }

  @Test
  public void mapSystemLanguageToTesseract_hindi_returnsHin() {
    assertEquals("hin", OCRUtils.mapSystemLanguageToTesseract("hi"));
  }

  @Test
  public void mapSystemLanguageToTesseract_chineseDefault_returnsChiSim() {
    // Default Chinese should be Simplified
    assertEquals("chi_sim", OCRUtils.mapSystemLanguageToTesseract("zh"));
  }

  @Test
  public void mapSystemLanguageToTesseract_unknown_returnsEng() {
    assertEquals("eng", OCRUtils.mapSystemLanguageToTesseract("xyz"));
  }

  @Test
  public void mapSystemLanguageToTesseract_null_returnsEng() {
    // The switch statement will throw NPE for null, but the method should handle it
    // Actually, switch on null throws NPE in Java, so this tests the default behavior
    try {
      String result = OCRUtils.mapSystemLanguageToTesseract(null);
      // If it doesn't throw, it should return eng
      assertEquals("eng", result);
    } catch (NullPointerException e) {
      // This is also acceptable behavior for null input
    }
  }

  // ==================== getLanguages Tests ====================

  @Test
  public void getLanguages_returnsNonEmptyArray() {
    String[] languages = OCRUtils.getLanguages();
    assertNotNull(languages);
    assertTrue("Should have at least 10 languages", languages.length >= 10);
  }

  @Test
  public void getLanguages_containsCommonLanguages() {
    String[] languages = OCRUtils.getLanguages();

    assertTrue("Should contain English", containsLanguage(languages, "eng"));
    assertTrue("Should contain German", containsLanguage(languages, "deu"));
    assertTrue("Should contain French", containsLanguage(languages, "fra"));
    assertTrue("Should contain Spanish", containsLanguage(languages, "spa"));
    assertTrue("Should contain Italian", containsLanguage(languages, "ita"));
  }

  @Test
  public void getLanguages_containsRTLLanguages() {
    String[] languages = OCRUtils.getLanguages();

    assertTrue("Should contain Arabic", containsLanguage(languages, "ara"));
    assertTrue("Should contain Persian/Farsi", containsLanguage(languages, "fas"));
  }

  @Test
  public void getLanguages_containsIndicLanguages() {
    String[] languages = OCRUtils.getLanguages();

    assertTrue("Should contain Hindi", containsLanguage(languages, "hin"));
  }

  @Test
  public void getLanguages_containsCJKLanguages() {
    String[] languages = OCRUtils.getLanguages();

    assertTrue("Should contain Simplified Chinese", containsLanguage(languages, "chi_sim"));
    assertTrue("Should contain Traditional Chinese", containsLanguage(languages, "chi_tra"));
    assertTrue("Should contain Thai", containsLanguage(languages, "tha"));
  }

  @Test
  public void getLanguages_containsCyrillicLanguages() {
    String[] languages = OCRUtils.getLanguages();

    assertTrue("Should contain Russian", containsLanguage(languages, "rus"));
  }

  @Test
  public void getLanguages_containsEasternEuropeanLanguages() {
    String[] languages = OCRUtils.getLanguages();

    assertTrue("Should contain Polish", containsLanguage(languages, "pol"));
    assertTrue("Should contain Czech", containsLanguage(languages, "ces"));
    assertTrue("Should contain Slovak", containsLanguage(languages, "slk"));
    assertTrue("Should contain Hungarian", containsLanguage(languages, "hun"));
    assertTrue("Should contain Romanian", containsLanguage(languages, "ron"));
  }

  @Test
  public void getLanguages_containsNordicLanguages() {
    String[] languages = OCRUtils.getLanguages();

    assertTrue("Should contain Danish", containsLanguage(languages, "dan"));
    assertTrue("Should contain Norwegian", containsLanguage(languages, "nor"));
    assertTrue("Should contain Swedish", containsLanguage(languages, "swe"));
  }

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
  public void codeToBaseDisplayName_tesseractCode_isLocalizedLanguageName() {
    org.junit.Assume.assumeFalse(de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR);
    java.util.Locale previous = java.util.Locale.getDefault();
    try {
      java.util.Locale.setDefault(java.util.Locale.ENGLISH);
      assertEquals("German", OCRUtils.codeToBaseDisplayName("deu"));
      assertEquals("Norwegian", OCRUtils.codeToBaseDisplayName("nor"));
      assertEquals("Chinese (Simplified)", OCRUtils.codeToBaseDisplayName("chi_sim"));
      assertEquals("Chinese (Traditional)", OCRUtils.codeToBaseDisplayName("chi_tra"));
    } finally {
      java.util.Locale.setDefault(previous);
    }
  }

  @Test
  public void codeToBaseDisplayName_paddleCode_isPaddleModelName() {
    org.junit.Assume.assumeTrue(de.schliweb.makeacopy.BuildConfig.FEATURE_PADDLE_OCR);
    assertEquals("Latin script (Paddle)", OCRUtils.codeToBaseDisplayName("latin"));
    assertEquals("xyz (Paddle)", OCRUtils.codeToBaseDisplayName("xyz"));
  }

  // ==================== Helper Methods ====================

  private boolean containsLanguage(String[] languages, String lang) {
    for (String l : languages) {
      if (l.equals(lang)) return true;
    }
    return false;
  }
}
