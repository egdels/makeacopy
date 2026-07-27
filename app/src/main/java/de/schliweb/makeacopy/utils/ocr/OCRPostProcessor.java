/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.ocr;

import android.util.Log;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Post-processor for OCR results that corrects common OCR errors. This class applies context-aware
 * corrections to improve OCR accuracy, particularly for words with low confidence scores.
 *
 * <p>Common OCR confusions handled: - 0 ↔ O (zero vs. letter O) - 1 ↔ l ↔ I (one vs. lowercase L
 * vs. uppercase I) - rn ↔ m (rn ligature confused with m) - cl ↔ d (cl confused with d) - vv ↔ w
 * (double v confused with w) - 5 ↔ S (five vs. letter S) - 8 ↔ B (eight vs. letter B)
 */
public class OCRPostProcessor {

  private static final String TAG = "OCRPostProcessor";

  /**
   * Confidence threshold below which words are considered for correction. Words with confidence >=
   * this value are assumed to be correct.
   */
  private static final float LOW_CONFIDENCE_THRESHOLD = 70.0f;

  /**
   * Confidence threshold above which words are trusted and no corrections are applied. Words with
   * confidence >= this value are assumed to be correctly recognized by OCR. This prevents false
   * corrections like "police" -> "pohce" when OCR is confident.
   */
  private static final float HIGH_CONFIDENCE_THRESHOLD = 85.0f;

  /**
   * Minimum word length for applying pattern-based corrections. Very short words are more prone to
   * false corrections.
   */
  private static final int MIN_WORD_LENGTH_FOR_PATTERN_CORRECTION = 3;

  // Common OCR error patterns (context-dependent)
  private static final Map<String, String> LETTER_IN_NUMBER_CONTEXT = new HashMap<>();
  private static final Map<String, String> NUMBER_IN_LETTER_CONTEXT = new HashMap<>();
  private static final Map<String, String> LIGATURE_CORRECTIONS = new HashMap<>();

  // Patterns for context detection
  private static final Pattern MOSTLY_DIGITS = Pattern.compile("^[0-9.,:\\-/]+$");
  private static final Pattern MOSTLY_LETTERS =
      Pattern.compile(
          "^[a-zA-ZäöüÄÖÜßàâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆáéíóúüñÁÉÍÓÚÜÑàèéìíîòóùúÀÈÉÌÍÎÒÓÙÚáàâãçéêíóôõúüÁÀÂÃÇÉÊÍÓÔÕÚÜİıĞğŞş]+$");
  private static final Pattern CONTAINS_LETTERS = Pattern.compile(".*[a-zA-Z].*");

  /**
   * Common OCR character confusions used for generating correction candidates. Maps characters to
   * their commonly confused alternatives.
   */
  private static final Map<Character, char[]> OCR_CONFUSIONS = new HashMap<>();

  static {
    // Letters that should be digits in numeric context
    LETTER_IN_NUMBER_CONTEXT.put("O", "0");
    LETTER_IN_NUMBER_CONTEXT.put("o", "0");
    LETTER_IN_NUMBER_CONTEXT.put("l", "1");
    LETTER_IN_NUMBER_CONTEXT.put("I", "1");
    LETTER_IN_NUMBER_CONTEXT.put("i", "1");
    LETTER_IN_NUMBER_CONTEXT.put("S", "5");
    LETTER_IN_NUMBER_CONTEXT.put("s", "5");
    LETTER_IN_NUMBER_CONTEXT.put("B", "8");
    LETTER_IN_NUMBER_CONTEXT.put("Z", "2");
    LETTER_IN_NUMBER_CONTEXT.put("z", "2");
    LETTER_IN_NUMBER_CONTEXT.put("G", "6");
    LETTER_IN_NUMBER_CONTEXT.put("g", "9");

    // Digits that should be letters in text context
    NUMBER_IN_LETTER_CONTEXT.put("0", "O");
    NUMBER_IN_LETTER_CONTEXT.put("1", "l");
    NUMBER_IN_LETTER_CONTEXT.put("5", "S");
    NUMBER_IN_LETTER_CONTEXT.put("8", "B");

    // Common ligature/character sequence confusions
    LIGATURE_CORRECTIONS.put("rn", "m");
    LIGATURE_CORRECTIONS.put("cl", "d");
    LIGATURE_CORRECTIONS.put("vv", "w");
    LIGATURE_CORRECTIONS.put("li", "h"); // sometimes li looks like h
    LIGATURE_CORRECTIONS.put("cI", "d"); // uppercase I confused
    LIGATURE_CORRECTIONS.put("c1", "d"); // digit 1 confused
  }

  static {
    // Digit-letter confusions
    OCR_CONFUSIONS.put('0', new char[] {'O', 'o', 'Q'});
    OCR_CONFUSIONS.put('O', new char[] {'0', 'Q', 'o'});
    OCR_CONFUSIONS.put('o', new char[] {'0', 'O'});
    OCR_CONFUSIONS.put('1', new char[] {'l', 'I', 'i', '|'});
    OCR_CONFUSIONS.put('l', new char[] {'1', 'I', 'i', '|'});
    OCR_CONFUSIONS.put('I', new char[] {'1', 'l', 'i', '|'});
    OCR_CONFUSIONS.put('i', new char[] {'1', 'l', 'I', 'j'});
    OCR_CONFUSIONS.put('5', new char[] {'S', 's'});
    OCR_CONFUSIONS.put('S', new char[] {'5', 's'});
    OCR_CONFUSIONS.put('s', new char[] {'5', 'S'});
    OCR_CONFUSIONS.put('8', new char[] {'B'});
    OCR_CONFUSIONS.put('B', new char[] {'8', 'R'});
    OCR_CONFUSIONS.put('2', new char[] {'Z', 'z'});
    OCR_CONFUSIONS.put('Z', new char[] {'2', 'z'});
    OCR_CONFUSIONS.put('z', new char[] {'2', 'Z'});
    OCR_CONFUSIONS.put('6', new char[] {'G', 'b'});
    OCR_CONFUSIONS.put('G', new char[] {'6', 'C'});
    OCR_CONFUSIONS.put('9', new char[] {'g', 'q'});
    OCR_CONFUSIONS.put('g', new char[] {'9', 'q'});
    // Letter confusions
    OCR_CONFUSIONS.put('m', new char[] {'n', 'r'}); // rn -> m
    OCR_CONFUSIONS.put('n', new char[] {'r', 'h'});
    OCR_CONFUSIONS.put('r', new char[] {'n'});
    OCR_CONFUSIONS.put('d', new char[] {'c', 'a'}); // cl -> d
    OCR_CONFUSIONS.put('c', new char[] {'e', 'o'});
    OCR_CONFUSIONS.put('e', new char[] {'c', 'o'});
    OCR_CONFUSIONS.put('w', new char[] {'v'}); // vv -> w
    OCR_CONFUSIONS.put('v', new char[] {'u', 'w'});
    OCR_CONFUSIONS.put('u', new char[] {'v', 'n'});
    OCR_CONFUSIONS.put('h', new char[] {'b', 'n'});
    OCR_CONFUSIONS.put('b', new char[] {'h', 'd', '6'});
    OCR_CONFUSIONS.put('a', new char[] {'o', 'e', 'ä'});
    OCR_CONFUSIONS.put('ä', new char[] {'a'});
    OCR_CONFUSIONS.put('ö', new char[] {'o'});
    OCR_CONFUSIONS.put('ü', new char[] {'u'});
    OCR_CONFUSIONS.put('ß', new char[] {'B', 's'});
    // Turkish-specific confusions
    OCR_CONFUSIONS.put('İ', new char[] {'I', 'i'});
    OCR_CONFUSIONS.put('ı', new char[] {'i', 'l', '1', 'I'});
    OCR_CONFUSIONS.put('ğ', new char[] {'g'});
    OCR_CONFUSIONS.put('ş', new char[] {'s'});
  }

  private OCRPostProcessor() {
    // Utility class
  }

  /**
   * Processes a list of recognized words and applies corrections to improve accuracy. Words with
   * low confidence are prioritized for correction.
   *
   * @param words The list of recognized words to process
   * @param language The language code (e.g., "deu", "eng") for language-specific corrections
   * @return A new list with corrected words (original list is not modified)
   */
  public static List<RecognizedWord> process(List<RecognizedWord> words, String language) {
    if (words == null || words.isEmpty()) {
      return words;
    }

    List<RecognizedWord> result = new ArrayList<>(words.size());
    int correctionCount = 0;

    for (RecognizedWord word : words) {
      String originalText = word.getText();
      String correctedText = correctWord(originalText, word.getConfidence(), language);

      if (!originalText.equals(correctedText)) {
        // Create new word with corrected text (RecognizedWord has setText)
        word.setText(correctedText);
        correctionCount++;
        Log.d(
            TAG,
            "Corrected: '"
                + originalText
                + "' -> '"
                + correctedText
                + "' (conf="
                + word.getConfidence()
                + ")");
      }
      result.add(word);
    }

    if (correctionCount > 0) {
      Log.i(TAG, "Post-processing corrected " + correctionCount + " words");
    }

    return result;
  }

  /**
   * Processes the full OCR text and applies corrections.
   *
   * @param text The full OCR text
   * @param language The language code
   * @return Corrected text
   */
  public static String processText(String text, String language) {
    if (text == null || text.isEmpty()) {
      return text;
    }

    String result = text;

    // Apply ligature corrections (these are almost always errors)
    result = applyLigatureCorrections(result);

    // Apply context-aware corrections for mixed alphanumeric sequences
    result = applyContextAwareCorrections(result);

    return result;
  }

  /**
   * Corrects a single word based on its confidence and context. Words with high confidence (>=
   * HIGH_CONFIDENCE_THRESHOLD) are trusted and not corrected.
   */
  @SuppressWarnings("UnusedVariable") // language kept for future language-specific corrections
  private static String correctWord(String word, float confidence, String language) {
    if (word == null || word.isEmpty()) {
      return word;
    }

    // Trust high-confidence words - don't apply any corrections
    // This prevents false corrections like "police" -> "pohce" when OCR is confident
    if (confidence >= HIGH_CONFIDENCE_THRESHOLD) {
      return word;
    }

    String result = word;

    // Apply ligature corrections for medium-confidence words
    result = applyLigatureCorrections(result);

    // For low-confidence words, apply more aggressive corrections
    if (confidence < LOW_CONFIDENCE_THRESHOLD) {
      result = applyContextAwareCorrections(result);
    }

    return result;
  }

  /**
   * Applies ligature corrections that are almost always OCR errors. These patterns (rn→m, cl→d,
   * vv→w) are very common OCR mistakes.
   */
  private static String applyLigatureCorrections(String text) {
    if (text == null || text.length() < MIN_WORD_LENGTH_FOR_PATTERN_CORRECTION) {
      return text;
    }

    String result = text;

    // Only apply ligature corrections in words that look like text (not numbers/codes)
    if (MOSTLY_LETTERS.matcher(result).matches()
        || (CONTAINS_LETTERS.matcher(result).matches()
            && !MOSTLY_DIGITS.matcher(result).matches())) {

      for (Map.Entry<String, String> entry : LIGATURE_CORRECTIONS.entrySet()) {
        String pattern = entry.getKey();
        String replacement = entry.getValue();

        // Be conservative: only replace if it results in a more "word-like" result
        if (result.contains(pattern)) {
          String candidate = result.replace(pattern, replacement);
          // Simple heuristic: prefer the shorter result for ligatures
          // (rn→m makes word shorter, which is usually correct)
          if (candidate.length() <= result.length()) {
            result = candidate;
          }
        }
      }
    }

    return result;
  }

  /**
   * Applies context-aware corrections for characters that are commonly confused. Uses surrounding
   * characters to determine if a character should be a letter or digit.
   */
  private static String applyContextAwareCorrections(String text) {
    if (text == null || text.length() < 2) {
      return text;
    }

    StringBuilder result = new StringBuilder();
    char[] chars = text.toCharArray();

    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      char corrected = c;

      // Determine context from surrounding characters
      boolean prevIsDigit = (i > 0) && Character.isDigit(chars[i - 1]);
      boolean nextIsDigit = (i < chars.length - 1) && Character.isDigit(chars[i + 1]);
      boolean prevIsLetter = (i > 0) && Character.isLetter(chars[i - 1]);
      boolean nextIsLetter = (i < chars.length - 1) && Character.isLetter(chars[i + 1]);

      // In numeric context (surrounded by digits), convert letters to digits
      if (prevIsDigit && nextIsDigit) {
        String replacement = LETTER_IN_NUMBER_CONTEXT.get(String.valueOf(c));
        if (replacement != null) {
          corrected = replacement.charAt(0);
        }
      }
      // In text context (surrounded by letters), convert digits to letters
      else if (prevIsLetter && nextIsLetter) {
        String replacement = NUMBER_IN_LETTER_CONTEXT.get(String.valueOf(c));
        if (replacement != null) {
          corrected = replacement.charAt(0);
        }
      }
      // At word boundaries, use majority context
      else if (i == 0 && nextIsDigit && !Character.isDigit(c)) {
        // Start of number sequence
        String replacement = LETTER_IN_NUMBER_CONTEXT.get(String.valueOf(c));
        if (replacement != null && isLikelyNumericSequence(text)) {
          corrected = replacement.charAt(0);
        }
      } else if (i == chars.length - 1 && prevIsDigit && !Character.isDigit(c)) {
        // End of number sequence
        String replacement = LETTER_IN_NUMBER_CONTEXT.get(String.valueOf(c));
        if (replacement != null && isLikelyNumericSequence(text)) {
          corrected = replacement.charAt(0);
        }
      }

      result.append(corrected);
    }

    return result.toString();
  }

  /**
   * Determines if a string is likely meant to be a numeric sequence (e.g., phone number, date, ID
   * number).
   */
  private static boolean isLikelyNumericSequence(String text) {
    if (text == null || text.isEmpty()) {
      return false;
    }

    int digitCount = 0;
    int letterCount = 0;

    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isDigit(c)) {
        digitCount++;
      } else if (Character.isLetter(c)) {
        letterCount++;
      }
    }

    // Consider it numeric if digits significantly outnumber letters
    return digitCount > letterCount * 2 && digitCount >= 2;
  }

  /**
   * Applies German-specific corrections. German has specific patterns like ß, umlauts, and compound
   * words.
   */
  public static String applyGermanCorrections(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }

    String result = text;

    // Common German OCR errors
    // B often misread as ß in certain fonts
    // But be careful: only in lowercase context
    // "Strabe" -> "Straße" (common error)
    result = result.replaceAll("(?<=[a-zäöü])B(?=[a-zäöü])", "ß");

    // "ss" at end of word after short vowel is often "ß" in old spelling
    // But modern German uses "ss", so we don't change this

    return result;
  }

  // ==================== Dictionary-based Correction ====================

  /**
   * Gets statistics about potential OCR errors in the word list.
   *
   * @param words The list of recognized words
   * @return Statistics about the OCR quality
   */
  public static OcrQualityStats analyzeQuality(List<RecognizedWord> words) {
    if (words == null || words.isEmpty()) {
      return new OcrQualityStats(0, 0, 0, 0, new ArrayList<>());
    }

    float totalConfidence = 0;
    int lowConfidenceCount = 0;
    List<String> suspiciousWords = new ArrayList<>();

    for (RecognizedWord word : words) {
      totalConfidence += word.getConfidence();

      if (word.getConfidence() < LOW_CONFIDENCE_THRESHOLD) {
        lowConfidenceCount++;
        if (suspiciousWords.size() < 20) { // Limit list size
          suspiciousWords.add(word.getText() + " (" + (int) word.getConfidence() + "%)");
        }
      }
    }

    float meanConfidence = totalConfidence / words.size();

    // Calculate median
    List<Float> confidences = new ArrayList<>();
    for (RecognizedWord w : words) {
      confidences.add(w.getConfidence());
    }
    confidences.sort(Float::compare);
    float medianConfidence = confidences.get(confidences.size() / 2);

    return new OcrQualityStats(
        meanConfidence, medianConfidence, lowConfidenceCount, words.size(), suspiciousWords);
  }

  /**
   * Processes a list of recognized words with dictionary-based correction using the provided
   * DictionaryManager. Words not found in the dictionary are corrected by trying common OCR error
   * substitutions.
   *
   * @param words The list of recognized words to process
   * @param language The language code (e.g., "deu", "eng")
   * @param dictManager The DictionaryManager instance to use for dictionary lookups
   * @return A new list with corrected words
   */
  public static List<RecognizedWord> processWithDictionary(
      List<RecognizedWord> words, String language, DictionaryManager dictManager) {
    if (words == null || words.isEmpty() || dictManager == null) {
      return process(words, language);
    }

    // Skip dictionary correction for non-word-based languages
    if (!DictionaryManager.isWordBasedLanguage(language)) {
      return process(words, language);
    }

    Set<String> dictionary = dictManager.getDictionary(language);

    if (dictionary.isEmpty()) {
      Log.d(TAG, "No dictionary available for " + language + ", using standard correction");
      return process(words, language);
    }

    List<RecognizedWord> result = new ArrayList<>(words.size());
    int correctionCount = 0;
    int dictCorrectionCount = 0;

    for (RecognizedWord word : words) {
      String originalText = word.getText();
      float confidence = word.getConfidence();
      Log.d(TAG, "Processing word: '" + originalText + "'" + " (conf=" + confidence + ")");

      // Trust high-confidence words - skip all corrections
      // This prevents false corrections like "police" -> "pohce" -> "ponce"
      if (confidence >= HIGH_CONFIDENCE_THRESHOLD) {
        Log.d(
            TAG,
            "High confidence ("
                + confidence
                + " >= "
                + HIGH_CONFIDENCE_THRESHOLD
                + "), skipping corrections");
        result.add(word);
        continue;
      }

      // First apply standard corrections
      String correctedText = correctWord(originalText, confidence, language);
      Log.d(TAG, "Standard correction: '" + originalText + "' -> '" + correctedText + "'");
      // Then try dictionary-based correction if word is not in dictionary
      String cleanWord = extractCleanWord(correctedText);
      if (!cleanWord.isEmpty()
          && cleanWord.length() >= 2
          && !dictionary.contains(cleanWord.toLowerCase(java.util.Locale.ROOT))) {

        String dictCorrected = findDictionaryCorrection(correctedText, dictionary);
        if (dictCorrected != null && !dictCorrected.equals(correctedText)) {
          Log.d(TAG, "Dictionary corrected: '" + correctedText + "' -> '" + dictCorrected + "'");
          correctedText = dictCorrected;
          dictCorrectionCount++;
        }
      }

      if (!originalText.equals(correctedText)) {
        word.setText(correctedText);
        correctionCount++;
        Log.d(
            TAG,
            "Corrected: '"
                + originalText
                + "' -> '"
                + correctedText
                + "' (conf="
                + confidence
                + ")");
      }
      result.add(word);
    }

    if (correctionCount > 0) {
      Log.i(
          TAG,
          "Post-processing corrected "
              + correctionCount
              + " words ("
              + dictCorrectionCount
              + " via dictionary)");
    }

    return result;
  }

  /** Extracts a clean word by removing leading/trailing punctuation. */
  private static String extractCleanWord(String word) {
    if (word == null || word.isEmpty()) {
      return "";
    }

    int start = 0;
    int end = word.length();

    while (start < end && !Character.isLetterOrDigit(word.charAt(start))) {
      start++;
    }
    while (end > start && !Character.isLetterOrDigit(word.charAt(end - 1))) {
      end--;
    }

    return start < end ? word.substring(start, end) : "";
  }

  /**
   * Finds a dictionary correction for a word by trying common OCR error substitutions.
   *
   * @param word The word to correct
   * @param dictionary The dictionary to check against
   * @return The corrected word, or null if no correction found
   */
  private static String findDictionaryCorrection(String word, Set<String> dictionary) {
    if (word == null || word.length() < 2 || dictionary.isEmpty()) {
      return null;
    }

    String cleanWord = extractCleanWord(word);
    if (cleanWord.isEmpty()) {
      return null;
    }

    // Try single character substitutions
    String singleSubResult = trySingleCharSubstitutions(word, cleanWord, dictionary);
    if (singleSubResult != null) {
      return singleSubResult;
    }

    // Try ligature expansions/contractions
    String ligatureResult = tryLigatureCorrections(word, cleanWord, dictionary);
    return ligatureResult;
  }

  /** Tries single character substitutions based on common OCR confusions. */
  private static String trySingleCharSubstitutions(
      String originalWord, String cleanWord, Set<String> dictionary) {
    char[] chars = cleanWord.toCharArray();

    for (int i = 0; i < chars.length; i++) {
      char original = chars[i];
      char[] confusions = OCR_CONFUSIONS.get(original);

      if (confusions != null) {
        for (char replacement : confusions) {
          chars[i] = replacement;
          String candidate = new String(chars);

          if (dictionary.contains(candidate.toLowerCase(java.util.Locale.ROOT))) {
            // Preserve original case pattern
            return preserveCasePattern(originalWord, cleanWord, candidate);
          }
        }
        chars[i] = original; // Reset for next iteration
      }
    }

    return null;
  }

  /** Tries ligature-based corrections (rn↔m, cl↔d, vv↔w). */
  private static String tryLigatureCorrections(
      String originalWord, String cleanWord, Set<String> dictionary) {
    // Try expanding ligatures
    String[][] expansions = {
      {"m", "rn"},
      {"w", "vv"},
      {"d", "cl"}
    };

    for (String[] pair : expansions) {
      String contracted = pair[0];
      String expanded = pair[1];

      // Try contraction (rn -> m)
      if (cleanWord.contains(expanded)) {
        String candidate = cleanWord.replace(expanded, contracted);
        if (dictionary.contains(candidate.toLowerCase(java.util.Locale.ROOT))) {
          return preserveCasePattern(originalWord, cleanWord, candidate);
        }
      }

      // Try expansion (m -> rn)
      if (cleanWord.contains(contracted)) {
        String candidate = cleanWord.replace(contracted, expanded);
        if (dictionary.contains(candidate.toLowerCase(java.util.Locale.ROOT))) {
          return preserveCasePattern(originalWord, cleanWord, candidate);
        }
      }
    }

    return null;
  }

  /** Preserves the case pattern from the original word when applying a correction. */
  private static String preserveCasePattern(
      String originalWord, String cleanWord, String corrected) {
    if (cleanWord.equals(originalWord)) {
      // No punctuation to preserve
      return applyCasePattern(cleanWord, corrected);
    }

    // Find where cleanWord starts in originalWord
    int startIdx = originalWord.indexOf(cleanWord.charAt(0));
    if (startIdx < 0) {
      startIdx = 0;
    }

    StringBuilder result = new StringBuilder();

    // Add leading punctuation
    result.append(originalWord.substring(0, startIdx));

    // Add corrected word with case pattern
    result.append(applyCasePattern(cleanWord, corrected));

    // Add trailing punctuation
    int endIdx = startIdx + cleanWord.length();
    if (endIdx < originalWord.length()) {
      result.append(originalWord.substring(endIdx));
    }

    return result.toString();
  }

  /** Applies the case pattern from the source word to the target word. */
  private static String applyCasePattern(String source, String target) {
    if (source.length() == 0 || target.length() == 0) {
      return target;
    }

    // Check if all uppercase
    if (source.equals(source.toUpperCase(java.util.Locale.ROOT))) {
      return target.toUpperCase(java.util.Locale.ROOT);
    }

    // Check if all lowercase
    if (source.equals(source.toLowerCase(java.util.Locale.ROOT))) {
      return target.toLowerCase(java.util.Locale.ROOT);
    }

    // Check if title case (first letter uppercase)
    if (Character.isUpperCase(source.charAt(0))) {
      return Character.toUpperCase(target.charAt(0))
          + (target.length() > 1 ? target.substring(1).toLowerCase(java.util.Locale.ROOT) : "");
    }

    return target.toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * Converts a list of recognized words to a single text string. Words are sorted by their vertical
   * position (top to bottom) and then horizontal position, grouped into lines based on vertical
   * proximity, and joined with spaces within lines and newlines between lines. Paragraphs are
   * detected based on larger vertical gaps between lines. HTML entities in the text are decoded.
   *
   * <p>For RTL (right-to-left) scripts like Arabic and Persian, words within a line are sorted from
   * right to left to preserve correct reading order.
   *
   * @param words The list of recognized words to convert
   * @return The extracted text with proper line breaks and paragraphs, or empty string if words is
   *     null or empty
   */
  public static String wordsToText(List<RecognizedWord> words) {
    if (words == null || words.isEmpty()) {
      return "";
    }

    // Vertical CJK layout (top-to-bottom columns, read right→left): the horizontal line
    // grouping below would merge all columns into one "line" sorted left→right, reversing
    // the column reading order. Detect the layout from the box geometry and reconstruct the
    // text column-wise instead. Horizontal (LTR/RTL) documents are unaffected.
    if (VerticalTextLayoutPolicy.isVerticalLayout(words)) {
      return verticalWordsToText(words);
    }

    // Sort words by position using a strict, transitive order. Line grouping below still decides
    // which nearby words belong together; the initial sort must not use pair-dependent thresholds
    // because TimSort rejects non-transitive comparators on dense Paddle OCR boxes.
    List<RecognizedWord> sorted = new ArrayList<>(words);
    sorted.sort(
        Comparator.comparingDouble((RecognizedWord word) -> word.getBoundingBox().top)
            .thenComparingDouble(word -> word.getBoundingBox().left)
            .thenComparingDouble(word -> word.getBoundingBox().bottom)
            .thenComparingDouble(word -> word.getBoundingBox().right));

    // Group words into lines based on vertical proximity
    List<List<RecognizedWord>> lines = new ArrayList<>();
    List<RecognizedWord> currentLine = new ArrayList<>();
    float lastMidY = Float.MIN_VALUE;
    float lineThreshold = 0;

    for (RecognizedWord word : sorted) {
      float midY = word.midY();
      float wordHeight = word.height();

      if (currentLine.isEmpty()) {
        currentLine.add(word);
        lastMidY = midY;
        lineThreshold = wordHeight * 0.6f;
      } else if (Math.abs(midY - lastMidY) <= lineThreshold) {
        // Same line
        currentLine.add(word);
        // Update threshold based on average height
        lineThreshold = Math.max(lineThreshold, wordHeight * 0.6f);
      } else {
        // New line
        lines.add(currentLine);
        currentLine = new ArrayList<>();
        currentLine.add(word);
        lastMidY = midY;
        lineThreshold = wordHeight * 0.6f;
      }
    }
    if (!currentLine.isEmpty()) {
      lines.add(currentLine);
    }

    // Calculate average line height for paragraph detection
    float totalLineHeight = 0;
    int lineCount = 0;
    for (List<RecognizedWord> line : lines) {
      for (RecognizedWord w : line) {
        totalLineHeight += w.height();
        lineCount++;
      }
    }
    float avgWordHeight = lineCount > 0 ? totalLineHeight / lineCount : 20f;
    // Paragraph threshold: gap larger than 1.5x average word height indicates new paragraph
    float paragraphThreshold = avgWordHeight * 1.5f;

    // Sort words within each line by X position and build text
    StringBuilder result = new StringBuilder();
    float lastLineBottomY = Float.MIN_VALUE;

    for (int i = 0; i < lines.size(); i++) {
      List<RecognizedWord> line = lines.get(i);

      // Determine if this line is predominantly RTL based on Unicode directionality
      boolean isRtlLine = isLineRtl(line);

      // Sort words within line: LTR = left to right, RTL = right to left
      if (isRtlLine) {
        // RTL: sort by X position descending (right to left)
        line.sort((a, b) -> Float.compare(b.getBoundingBox().left, a.getBoundingBox().left));
      } else {
        // LTR: sort by X position ascending (left to right)
        line.sort((a, b) -> Float.compare(a.getBoundingBox().left, b.getBoundingBox().left));
      }

      // Calculate line's top Y for paragraph detection
      float lineTopY = Float.MAX_VALUE;
      for (RecognizedWord w : line) {
        lineTopY = Math.min(lineTopY, w.getBoundingBox().top);
      }

      // Check for paragraph break (large vertical gap)
      if (i > 0 && lastLineBottomY != Float.MIN_VALUE) {
        float gap = lineTopY - lastLineBottomY;
        if (gap > paragraphThreshold) {
          // Insert extra newline for paragraph break
          result.append("\n");
        }
      }

      for (int j = 0; j < line.size(); j++) {
        if (j > 0) {
          result.append(" ");
        }
        // Decode HTML entities in the word text. RecognizedWord.text is expected to be stored in
        // logical Unicode order; RTL handling here is limited to line ordering by geometry.
        result.append(decodeHtmlEntities(line.get(j).getText()));
      }

      if (i < lines.size() - 1) {
        result.append("\n");
      }

      // Update last line bottom Y
      float lineBottomY = Float.MIN_VALUE;
      for (RecognizedWord w : line) {
        lineBottomY = Math.max(lineBottomY, w.getBoundingBox().bottom);
      }
      lastLineBottomY = lineBottomY;
    }

    return result.toString();
  }

  /**
   * Reconstructs the text of a vertical (CJK top-to-bottom) page: columns are ordered
   * right-to-left, segments within a column top-to-bottom. Segments of the same column are
   * concatenated without artificial spaces; complete columns are separated by a newline. The column
   * grouping is delegated to {@link VerticalTextLayoutPolicy#groupIntoVerticalColumns} so the
   * ordering is deterministic and covered by JVM unit tests.
   *
   * @param words the recognized words (non-null, non-empty)
   * @return the reconstructed text in vertical reading order
   */
  private static String verticalWordsToText(List<RecognizedWord> words) {
    int n = words.size();
    float[] lefts = new float[n];
    float[] tops = new float[n];
    float[] rights = new float[n];
    float[] bottoms = new float[n];
    for (int i = 0; i < n; i++) {
      android.graphics.RectF r = words.get(i).getBoundingBox();
      lefts[i] = r.left;
      tops[i] = r.top;
      rights[i] = r.right;
      bottoms[i] = r.bottom;
    }
    List<int[]> columns =
        VerticalTextLayoutPolicy.groupIntoVerticalColumns(lefts, tops, rights, bottoms);
    StringBuilder result = new StringBuilder();
    for (int c = 0; c < columns.size(); c++) {
      if (c > 0) {
        result.append('\n');
      }
      for (int idx : columns.get(c)) {
        String text = words.get(idx).getText();
        if (text == null) continue;
        result.append(decodeHtmlEntities(text));
      }
    }
    return result.toString();
  }

  /**
   * Determines if a line of words is predominantly RTL (right-to-left) based on Unicode
   * directionality analysis of the text content.
   *
   * <p>This method counts RTL and LTR characters in all words of the line and returns true if RTL
   * characters are the majority.
   *
   * @param line The list of words in the line
   * @return true if the line is predominantly RTL, false otherwise
   */
  private static boolean isLineRtl(List<RecognizedWord> line) {
    if (line == null || line.isEmpty()) {
      return false;
    }

    int rtlCount = 0;
    int ltrCount = 0;

    for (RecognizedWord word : line) {
      String text = word.getText();
      if (text == null) continue;

      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        byte directionality = Character.getDirectionality(c);

        if (directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT
            || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
            || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE) {
          rtlCount++;
        } else if (directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT
            || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
            || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE) {
          ltrCount++;
        }
        // Neutral characters (spaces, punctuation, digits) are not counted
      }
    }

    // Line is RTL if RTL characters are the majority
    return rtlCount > ltrCount;
  }

  /**
   * Decodes common HTML entities in a string. Handles numeric entities (&#39;, &#34;, etc.) and
   * named entities (&amp;, &lt;, &gt;, &quot;, &apos;).
   *
   * @param text The text containing HTML entities
   * @return The decoded text with entities replaced by their character equivalents
   */
  private static String decodeHtmlEntities(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }

    String result = text;

    // Decode numeric entities (decimal): &#39; -> '
    java.util.regex.Matcher decimalMatcher = Pattern.compile("&#(\\d+);").matcher(result);
    StringBuffer sb = new StringBuffer();
    while (decimalMatcher.find()) {
      try {
        int codePoint = Integer.parseInt(decimalMatcher.group(1));
        decimalMatcher.appendReplacement(
            sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) codePoint)));
      } catch (NumberFormatException e) {
        // Keep original if parsing fails
      }
    }
    decimalMatcher.appendTail(sb);
    result = sb.toString();

    // Decode numeric entities (hexadecimal): &#x27; -> '
    java.util.regex.Matcher hexMatcher = Pattern.compile("&#[xX]([0-9a-fA-F]+);").matcher(result);
    sb = new StringBuffer();
    while (hexMatcher.find()) {
      try {
        int codePoint = Integer.parseInt(hexMatcher.group(1), 16);
        hexMatcher.appendReplacement(
            sb, java.util.regex.Matcher.quoteReplacement(String.valueOf((char) codePoint)));
      } catch (NumberFormatException e) {
        // Keep original if parsing fails
      }
    }
    hexMatcher.appendTail(sb);
    result = sb.toString();

    // Decode common named entities
    result = result.replace("&amp;", "&");
    result = result.replace("&lt;", "<");
    result = result.replace("&gt;", ">");
    result = result.replace("&quot;", "\"");
    result = result.replace("&apos;", "'");
    result = result.replace("&nbsp;", " ");

    return result;
  }

  /** Statistics about OCR quality for a set of recognized words. */
  public record OcrQualityStats(
      float meanConfidence,
      float medianConfidence,
      int lowConfidenceWordCount,
      int totalWordCount,
      List<String> suspiciousWords) {

    public float getLowConfidenceRatio() {
      return totalWordCount > 0 ? (float) lowConfidenceWordCount / totalWordCount : 0;
    }

    @Override
    public String toString() {
      return "OcrQualityStats{"
          + "meanConf="
          + String.format(Locale.ROOT, "%.1f", meanConfidence)
          + ", medianConf="
          + String.format(Locale.ROOT, "%.1f", medianConfidence)
          + ", lowConfWords="
          + lowConfidenceWordCount
          + "/"
          + totalWordCount
          + " ("
          + String.format(Locale.ROOT, "%.1f", getLowConfidenceRatio() * 100)
          + "%)"
          + '}';
    }
  }
}
