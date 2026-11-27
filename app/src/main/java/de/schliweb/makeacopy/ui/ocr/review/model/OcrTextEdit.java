/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.ui.ocr.review.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes an edited page text back onto the words of an {@link OcrDoc}.
 *
 * <p>The text is compared with the pieces it was built from (a word on a horizontal page, a column
 * on a vertical one), so only what the user touched changes: untouched words keep their text, box
 * and place however many words were added or removed before them.
 *
 * <ul>
 *   <li>a changed word keeps its box,
 *   <li>a word typed apart ("brownfox" to "brown fox") shares its box between the parts,
 *   <li>words joined ("The quick" to "Thequick") become one word over both boxes,
 *   <li>a new word takes a share of the box of the word next to it,
 *   <li>a deleted word is removed.
 * </ul>
 */
public final class OcrTextEdit {
  // Above this many cells the changed stretch is taken as one replacement instead of compared
  private static final long MAX_COMPARE_CELLS = 4_000_000L;

  private OcrTextEdit() {}

  /** The text as the editor holds it, cut into the pieces the words are compared with. */
  public static final class Tokens {
    final List<String> texts = new ArrayList<>();
    final List<Boolean> startsLine = new ArrayList<>();

    /**
     * Cuts the edited text into pieces.
     *
     * @param text the edited text
     * @param lines {@code true}: every non-blank line is one piece (vertical page); {@code false}:
     *     every run of non-whitespace is one piece
     */
    public static Tokens of(String text, boolean lines) {
      Tokens tokens = new Tokens();
      if (text == null) return tokens;
      for (String line : text.split("\\R", -1)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) continue;
        if (lines) {
          tokens.texts.add(trimmed);
          tokens.startsLine.add(true);
          continue;
        }
        boolean first = true;
        for (String token : trimmed.split("\\s+", -1)) {
          tokens.texts.add(token);
          tokens.startsLine.add(first);
          first = false;
        }
      }
      return tokens;
    }
  }

  /**
   * Carries the edited text over to the words of the document.
   *
   * @param doc the document to change in place
   * @param pieces the words of each piece of the text, in text order (see the class comment); words
   *     that are in no piece stay as they are
   * @param pieceTexts the text each piece shows
   * @param tokens the edited text
   * @param columns whether the pieces are the columns of a vertical page, read right to left
   * @return whether the document changed
   */
  public static boolean apply(
      OcrDoc doc,
      List<List<OcrDoc.Word>> pieces,
      List<String> pieceTexts,
      Tokens tokens,
      boolean columns) {
    int oldCount = pieces.size();
    int newCount = tokens.texts.size();
    if (oldCount == 0) return false;

    // match[i]: index of the token that piece i still is, or -1
    int[] match = matchPieces(pieceTexts, tokens.texts);
    lendNeighbours(match, tokens, oldCount, newCount);

    boolean changed = false;
    int nextId = 1;
    for (OcrDoc.Word w : doc.words) if (w != null) nextId = Math.max(nextId, w.id + 1);

    int oldAt = 0;
    int newAt = 0;
    while (oldAt < oldCount || newAt < newCount) {
      if (oldAt < oldCount && match[oldAt] == newAt) {
        oldAt++;
        newAt++;
        continue;
      }
      int oldEnd = oldAt;
      while (oldEnd < oldCount && match[oldEnd] < 0) oldEnd++;
      int newEnd = oldEnd < oldCount ? match[oldEnd] : newCount;
      if (oldEnd > oldAt) {
        nextId =
            replace(
                doc,
                pieces.subList(oldAt, oldEnd),
                pieceTexts.subList(oldAt, oldEnd),
                tokens.texts.subList(newAt, newEnd),
                columns,
                nextId);
        changed = true;
      }
      oldAt = oldEnd;
      newAt = newEnd;
    }
    return changed;
  }

  // ---------------------------------------------------------------------------------------------
  // Which pieces are still there
  // ---------------------------------------------------------------------------------------------

  private static int[] matchPieces(List<String> oldTexts, List<String> newTexts) {
    int oldCount = oldTexts.size();
    int newCount = newTexts.size();
    int[] match = new int[oldCount];
    java.util.Arrays.fill(match, -1);

    int head = 0;
    while (head < oldCount && head < newCount && oldTexts.get(head).equals(newTexts.get(head))) {
      match[head] = head;
      head++;
    }
    int tail = 0;
    while (tail < oldCount - head
        && tail < newCount - head
        && oldTexts.get(oldCount - 1 - tail).equals(newTexts.get(newCount - 1 - tail))) {
      match[oldCount - 1 - tail] = newCount - 1 - tail;
      tail++;
    }

    int rows = oldCount - head - tail;
    int cols = newCount - head - tail;
    if (rows == 0 || cols == 0 || (long) rows * cols > MAX_COMPARE_CELLS) return match;

    // Longest common subsequence of the stretch in between
    int[][] len = new int[rows + 1][cols + 1];
    for (int i = rows - 1; i >= 0; i--) {
      for (int j = cols - 1; j >= 0; j--) {
        len[i][j] =
            oldTexts.get(head + i).equals(newTexts.get(head + j))
                ? len[i + 1][j + 1] + 1
                : Math.max(len[i + 1][j], len[i][j + 1]);
      }
    }
    int i = 0;
    int j = 0;
    while (i < rows && j < cols) {
      if (oldTexts.get(head + i).equals(newTexts.get(head + j))) {
        match[head + i] = head + j;
        i++;
        j++;
      } else if (len[i + 1][j] >= len[i][j + 1]) {
        i++;
      } else {
        j++;
      }
    }
    return match;
  }

  /**
   * New text between two untouched pieces has no box of its own: the piece next to it gives up its
   * match, so the new text shares that piece's box. The piece before is taken, unless the new text
   * starts a line that the piece after continues.
   */
  private static void lendNeighbours(int[] match, Tokens tokens, int oldCount, int newCount) {
    int before = -1; // the still matched piece before the gap, -1: none
    int beforeToken = -1;
    boolean beforeLent = false; // it gave up its match already and is part of this gap
    for (int piece = 0; piece <= oldCount; piece++) {
      if (piece < oldCount && match[piece] < 0) continue;
      int token = piece < oldCount ? match[piece] : newCount;
      boolean newText = token > beforeToken + 1;
      boolean hasPieces = piece - before > 1 || beforeLent;
      boolean lent = false;
      if (newText && !hasPieces) {
        boolean hasBefore = before >= 0;
        boolean hasAfter = piece < oldCount;
        boolean continuedByAfter =
            hasAfter && tokens.startsLine.get(beforeToken + 1) && !tokens.startsLine.get(token);
        if (hasAfter && (!hasBefore || continuedByAfter)) {
          match[piece] = -1;
          lent = true;
        } else if (hasBefore) {
          match[before] = -1;
        }
      }
      before = piece;
      beforeToken = token;
      beforeLent = lent;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // One changed stretch
  // ---------------------------------------------------------------------------------------------

  private static int replace(
      OcrDoc doc,
      List<List<OcrDoc.Word>> pieces,
      List<String> pieceTexts,
      List<String> texts,
      boolean columns,
      int nextId) {
    int oldCount = pieces.size();
    int newCount = texts.size();

    int[] owner = owners(pieceTexts, texts);

    int[][] boxes = new int[oldCount][];
    int[] firstToken = new int[oldCount];
    int[] tokenCount = new int[oldCount];
    java.util.Arrays.fill(firstToken, -1);
    for (int i = 0; i < oldCount; i++) boxes[i] = union(pieces.get(i));
    for (int j = 0; j < newCount; j++) {
      if (firstToken[owner[j]] < 0) firstToken[owner[j]] = j;
      tokenCount[owner[j]]++;
    }

    // A piece without text goes into the token next to it when both are on one line
    int[][] tokenBoxes = new int[newCount][];
    int[][] extra = new int[newCount][];
    for (int i = 0; i < oldCount; i++) {
      if (tokenCount[i] > 0) continue;
      int target = -1;
      for (int k = i + 1; k < oldCount && target < 0; k++) {
        if (tokenCount[k] > 0) target = firstToken[k];
      }
      if (target < 0 || !sameLine(boxes[i], boxes[owner[target]], columns)) {
        target = -1;
        for (int k = i - 1; k >= 0 && target < 0; k--) {
          if (tokenCount[k] > 0) target = firstToken[k] + tokenCount[k] - 1;
        }
        if (target >= 0 && !sameLine(boxes[i], boxes[owner[target]], columns)) target = -1;
      }
      if (target >= 0)
        extra[target] = extra[target] == null ? boxes[i] : join(extra[target], boxes[i]);
    }

    for (int i = 0; i < oldCount; i++) {
      if (tokenCount[i] == 0) continue;
      int from = firstToken[i];
      int[][] parts = split(boxes[i], texts.subList(from, from + tokenCount[i]), columns);
      for (int q = 0; q < parts.length; q++) {
        tokenBoxes[from + q] = extra[from + q] == null ? parts[q] : join(parts[q], extra[from + q]);
      }
    }

    // Write: the first token of a piece keeps the piece's first word, further tokens are new words
    for (int i = 0; i < oldCount; i++) {
      List<OcrDoc.Word> members = pieces.get(i);
      OcrDoc.Word keeper = members.get(0);
      if (tokenCount[i] == 0) {
        for (OcrDoc.Word member : members) remove(doc, member);
        continue;
      }
      for (int m = 1; m < members.size(); m++) remove(doc, members.get(m));
      int from = firstToken[i];
      keeper.t = texts.get(from);
      keeper.b = tokenBoxes[from];
      keeper.e = true;
      OcrDoc.Word after = keeper;
      for (int q = 1; q < tokenCount[i]; q++) {
        OcrDoc.Word added = new OcrDoc.Word();
        added.id = nextId++;
        added.t = texts.get(from + q);
        added.b = tokenBoxes[from + q];
        added.c = keeper.c;
        added.e = true;
        added.l = keeper.l;
        added.k = keeper.k;
        added.lang = keeper.lang;
        insertAfter(doc, after, added);
        after = added;
      }
    }
    return nextId;
  }

  /**
   * The piece each token of a changed stretch is written to: the one most of its letters come from
   * ("brown" and "fox" both from "brownfox"); a token with no letter in common goes by its position
   * in the stretch. Owners never run backwards.
   */
  private static int[] owners(List<String> pieceTexts, List<String> texts) {
    int oldCount = pieceTexts.size();
    int newCount = texts.size();
    int[] owner = new int[newCount];
    java.util.Arrays.fill(owner, -1);
    if (newCount == 0) return owner;

    StringBuilder oldChars = new StringBuilder();
    StringBuilder newChars = new StringBuilder();
    for (String text : pieceTexts) oldChars.append(text);
    for (String text : texts) newChars.append(text);
    int rows = oldChars.length();
    int cols = newChars.length();
    int[] pieceOfChar = new int[rows];
    int[] tokenOfChar = new int[cols];
    for (int i = 0, at = 0; i < oldCount; i++) {
      for (int c = 0; c < pieceTexts.get(i).length(); c++) pieceOfChar[at++] = i;
    }
    for (int j = 0, at = 0; j < newCount; j++) {
      for (int c = 0; c < texts.get(j).length(); c++) tokenOfChar[at++] = j;
    }

    if (rows > 0 && cols > 0 && (long) rows * cols <= MAX_COMPARE_CELLS) {
      int[][] len = new int[rows + 1][cols + 1];
      for (int i = rows - 1; i >= 0; i--) {
        for (int j = cols - 1; j >= 0; j--) {
          len[i][j] =
              oldChars.charAt(i) == newChars.charAt(j)
                  ? len[i + 1][j + 1] + 1
                  : Math.max(len[i + 1][j], len[i][j + 1]);
        }
      }
      int[][] common = new int[newCount][oldCount];
      int i = 0;
      int j = 0;
      while (i < rows && j < cols) {
        if (oldChars.charAt(i) == newChars.charAt(j)) {
          common[tokenOfChar[j]][pieceOfChar[i]]++;
          i++;
          j++;
        } else if (len[i + 1][j] >= len[i][j + 1]) {
          i++;
        } else {
          j++;
        }
      }
      for (int t = 0; t < newCount; t++) {
        int best = 0;
        for (int piece = 0; piece < oldCount; piece++) {
          if (common[t][piece] > best) {
            best = common[t][piece];
            owner[t] = piece;
          }
        }
      }
    }

    // Tokens with nothing in common: by position, between the owners around them
    int oldLength = Math.max(1, rows);
    int newLength = Math.max(1, cols);
    int at = 0;
    for (int t = 0; t < newCount; t++) {
      int tokenLength = texts.get(t).length();
      if (owner[t] < 0) {
        int low = t > 0 ? owner[t - 1] : 0;
        int high = oldCount - 1;
        for (int next = t + 1; next < newCount; next++) {
          if (owner[next] >= 0) {
            high = owner[next];
            break;
          }
        }
        int charAt = (int) ((at + tokenLength / 2.0) * oldLength / newLength);
        int piece = rows > 0 ? pieceOfChar[Math.min(rows - 1, charAt)] : 0;
        owner[t] = Math.max(low, Math.min(high, piece));
      }
      at += tokenLength;
    }
    return owner;
  }

  // ---------------------------------------------------------------------------------------------
  // Boxes ([x, y, w, h])
  // ---------------------------------------------------------------------------------------------

  private static int[] union(List<OcrDoc.Word> words) {
    int[] box = null;
    for (OcrDoc.Word word : words) box = box == null ? word.b.clone() : join(box, word.b);
    return box;
  }

  private static int[] join(int[] a, int[] b) {
    int left = Math.min(a[0], b[0]);
    int top = Math.min(a[1], b[1]);
    int right = Math.max(a[0] + a[2], b[0] + b[2]);
    int bottom = Math.max(a[1] + a[3], b[1] + b[3]);
    return new int[] {left, top, right - left, bottom - top};
  }

  /** Whether two boxes share a line (a column on a vertical page): they overlap across it. */
  private static boolean sameLine(int[] a, int[] b, boolean columns) {
    int axis = columns ? 0 : 1;
    int overlap =
        Math.min(a[axis] + a[axis + 2], b[axis] + b[axis + 2]) - Math.max(a[axis], b[axis]);
    return overlap * 2 > Math.min(a[axis + 2], b[axis + 2]);
  }

  /** Shares a box between texts side by side, by their lengths, in reading direction. */
  private static int[][] split(int[] box, List<String> texts, boolean columns) {
    int count = texts.size();
    int[][] parts = new int[count][];
    if (count == 1) {
      parts[0] = box.clone();
      return parts;
    }
    int total = 0;
    StringBuilder all = new StringBuilder();
    for (String text : texts) {
      total += Math.max(1, length(text));
      all.append(text);
    }
    boolean rightToLeft = columns || isRtl(all.toString());
    int done = 0;
    for (int q = 0; q < count; q++) {
      int from = (int) ((long) box[2] * done / total);
      done += Math.max(1, length(texts.get(q)));
      int to = (int) ((long) box[2] * done / total);
      int x = rightToLeft ? box[0] + box[2] - to : box[0] + from;
      parts[q] = new int[] {x, box[1], to - from, box[3]};
    }
    return parts;
  }

  private static int length(String text) {
    return text.codePointCount(0, text.length());
  }

  /** Whether the text has more right-to-left than left-to-right letters. */
  static boolean isRtl(String text) {
    int rtl = 0;
    int ltr = 0;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      byte direction = Character.getDirectionality(cp);
      if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
          || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
        rtl++;
      } else if (direction == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
        ltr++;
      }
      i += Character.charCount(cp);
    }
    return rtl > ltr;
  }

  // ---------------------------------------------------------------------------------------------
  // Words and the lines that list them
  // ---------------------------------------------------------------------------------------------

  private static void remove(OcrDoc doc, OcrDoc.Word word) {
    doc.words.remove(word);
    if (doc.lines == null) return;
    for (OcrDoc.Line line : doc.lines) {
      if (line == null || line.w == null) continue;
      int at = indexOf(line.w, word.id);
      if (at < 0) continue;
      int[] ids = new int[line.w.length - 1];
      System.arraycopy(line.w, 0, ids, 0, at);
      System.arraycopy(line.w, at + 1, ids, at, ids.length - at);
      line.w = ids;
    }
  }

  private static void insertAfter(OcrDoc doc, OcrDoc.Word before, OcrDoc.Word added) {
    int index = doc.words.indexOf(before);
    doc.words.add(index < 0 ? doc.words.size() : index + 1, added);
    if (doc.lines == null) return;
    for (OcrDoc.Line line : doc.lines) {
      if (line == null || line.w == null) continue;
      int at = indexOf(line.w, before.id);
      if (at < 0) continue;
      int[] ids = new int[line.w.length + 1];
      System.arraycopy(line.w, 0, ids, 0, at + 1);
      ids[at + 1] = added.id;
      System.arraycopy(line.w, at + 1, ids, at + 2, line.w.length - at - 1);
      line.w = ids;
      return;
    }
  }

  private static int indexOf(int[] ids, int id) {
    for (int i = 0; i < ids.length; i++) if (ids[i] == id) return i;
    return -1;
  }
}
