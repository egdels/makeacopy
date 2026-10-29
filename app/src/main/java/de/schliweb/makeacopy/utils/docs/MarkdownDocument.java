/*
 * Copyright 2026 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.makeacopy.utils.docs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The help, the privacy policy and the open-source licenses (HELP*.md in help/,
 * docs/_includes/privacy.md, OPEN-SOURCE-LICENSES.md), parsed into blocks of styled spans that
 * DocumentActivity renders as TextViews. Only the subset of Markdown those files use: a "#" title,
 * "##" headings, paragraphs, "-" bullets with indented continuation lines, inline {@code `code`},
 * {@code **bold**} and {@code *italic*}, addresses in angle brackets ({@code <https://...>}, {@code
 * <name@example.org>}), which the web page turns into links, and fenced blocks between two {@code
 * ```} lines. A fenced block holds text that must stay as written, such as a license: each of its
 * paragraphs becomes a {@link BlockType#VERBATIM} block without any inline markup, see {@link
 * #addFenced}. The files deliberately use no tables or link syntax so that this stays small.
 */
public record MarkdownDocument(String title, List<Block> blocks) {

  public enum BlockType {
    HEADING,
    PARAGRAPH,
    BULLET,
    /**
     * A paragraph from a fenced block: plain text, shown like a paragraph. It keeps the line breaks
     * that belong to the text as "\n".
     */
    VERBATIM
  }

  public record Block(BlockType type, List<Span> spans) {}

  /** A run of text with one style; {@code code} text is shown in a monospace font. */
  public record Span(String text, boolean bold, boolean italic, boolean code) {}

  private static final Pattern TRAILING_SPACE = Pattern.compile("\\s+$");

  /** A line of only "-" or "=" that underlines a heading in a plain-text license. */
  private static final Pattern UNDERLINE = Pattern.compile("\\s*(-{3,}|={3,})\\s*");

  /** A line of only "*" that opens or closes a framed passage, as in the MPL. */
  private static final Pattern FRAME = Pattern.compile("\\s*\\*{10,}\\s*");

  /**
   * A list item, a web address, a copyright line or "All rights reserved" starts a line of its own
   * in a license.
   */
  private static final Pattern LINE_START =
      Pattern.compile(
          "(\\*|-|•|\\d+(\\.\\d+)*\\.|\\([a-z0-9]+\\))\\s.*|https?://.*|(Copyright|All rights"
              + " reserved)\\b.*");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private static final Pattern INLINE =
      Pattern.compile(
          "`([^`]+)`|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|<((?:https://|[^\\s<>@]+@)[^\\s<>]+)>");

  /** {@code fallbackTitle} is used when the text has no "#" line. */
  public static MarkdownDocument parse(String markdown, String fallbackTitle) {
    Parser parser = new Parser();
    String title = fallbackTitle;
    for (String raw : markdown.split("\\r?\\n", -1)) {
      String line = TRAILING_SPACE.matcher(raw).replaceFirst("");
      if (line.trim().startsWith("```")) {
        parser.flush();
        if (parser.fenced == null) {
          parser.fenced = new ArrayList<>();
        } else {
          addFenced(parser.fenced, parser.blocks);
          parser.fenced = null;
        }
        continue;
      }
      if (parser.fenced != null) {
        parser.fenced.add(line);
        continue;
      }
      if (line.isEmpty()) {
        parser.flush();
      } else if (line.startsWith("# ")) {
        parser.flush();
        title = line.substring(2).trim();
      } else if (line.startsWith("## ")) {
        parser.flush();
        parser.blocks.add(new Block(BlockType.HEADING, inline(line.substring(3).trim())));
      } else if (line.startsWith("- ")) {
        parser.flush();
        parser.bullet = new StringBuilder(line.substring(2).trim());
      } else if (parser.bullet != null && raw.startsWith("  ")) {
        parser.bullet.append(' ').append(line.trim());
      } else {
        if (parser.bullet != null) {
          parser.flush();
        }
        if (parser.paragraph.length() > 0) {
          parser.paragraph.append(' ');
        }
        parser.paragraph.append(line.trim());
      }
    }
    parser.flush();
    if (parser.fenced != null) {
      addFenced(parser.fenced, parser.blocks);
    }
    return new MarkdownDocument(title, Collections.unmodifiableList(parser.blocks));
  }

  /**
   * Turns the lines of a fenced block into VERBATIM paragraphs. The lines were wrapped for a fixed
   * width, so they are joined again, except where the break is part of the text: before a list
   * item, a web address, a copyright line or "All rights reserved", and after a line that leaves
   * room for the next word, such as a name above its copyright or a centred title. A line indented
   * deeper than the one before continues it (a hanging indent). Heading underlines end a paragraph;
   * the frame of asterisks around a passage is dropped.
   */
  private static void addFenced(List<String> fenced, List<Block> out) {
    List<String> lines = new ArrayList<>();
    boolean framed = false;
    for (String line : fenced) {
      if (FRAME.matcher(line).matches()) {
        framed = true;
        lines.add("");
        continue;
      }
      framed = framed && line.trim().startsWith("*");
      String content = line;
      if (framed) {
        // The frame's left edge, and its right edge where it has one
        content = line.trim().substring(1);
        if (content.endsWith("*")) {
          content = content.substring(0, content.length() - 1);
        }
      }
      lines.add(UNDERLINE.matcher(content).matches() ? "" : content);
    }
    int width = 0;
    for (String line : lines) {
      width = Math.max(width, line.trim().length());
    }
    StringBuilder text = new StringBuilder();
    String previous = null;
    for (String line : lines) {
      if (line.trim().isEmpty()) {
        addVerbatim(text, out);
        previous = null;
        continue;
      }
      if (previous != null) {
        text.append(lineBreakBetween(previous, line, width) ? '\n' : ' ');
      }
      // Runs of spaces only lined text up in the file
      text.append(WHITESPACE.matcher(line.trim()).replaceAll(" "));
      previous = line;
    }
    addVerbatim(text, out);
  }

  private static boolean lineBreakBetween(String line, String next, int width) {
    String nextText = next.trim();
    if (LINE_START.matcher(nextText).matches()) {
      return true;
    }
    if (indent(next) > indent(line)) {
      return false;
    }
    int nextWord = WHITESPACE.split(nextText, 2)[0].length();
    return line.trim().length() + 1 + nextWord <= width * 3 / 4;
  }

  private static int indent(String line) {
    int n = 0;
    while (n < line.length() && Character.isWhitespace(line.charAt(n))) {
      n++;
    }
    return n;
  }

  private static void addVerbatim(StringBuilder text, List<Block> out) {
    if (text.length() > 0) {
      out.add(
          new Block(BlockType.VERBATIM, List.of(new Span(text.toString(), false, false, false))));
      text.setLength(0);
    }
  }

  private static final class Parser {
    final List<Block> blocks = new ArrayList<>();
    final StringBuilder paragraph = new StringBuilder();
    StringBuilder bullet;

    /** The lines of the fenced block being read, or null outside one. */
    List<String> fenced;

    void flush() {
      if (bullet != null) {
        blocks.add(new Block(BlockType.BULLET, inline(bullet.toString())));
        bullet = null;
      }
      if (paragraph.length() > 0) {
        blocks.add(new Block(BlockType.PARAGRAPH, inline(paragraph.toString())));
        paragraph.setLength(0);
      }
    }
  }

  static List<Span> inline(String text) {
    List<Span> spans = new ArrayList<>();
    appendInline(text, false, false, spans);
    return Collections.unmodifiableList(spans);
  }

  /** Styled spans may nest (code inside italic), so the inner text is parsed again. */
  private static void appendInline(String text, boolean bold, boolean italic, List<Span> out) {
    Matcher m = INLINE.matcher(text);
    int last = 0;
    while (m.find()) {
      addPlain(text.substring(last, m.start()), bold, italic, out);
      if (m.group(1) != null) {
        out.add(new Span(m.group(1), bold, italic, true));
      } else if (m.group(2) != null) {
        appendInline(m.group(2), true, italic, out);
      } else if (m.group(3) != null) {
        appendInline(m.group(3), bold, true, out);
      } else {
        // <address>: the brackets make the web page link it; the app links plain text itself
        addPlain(m.group(4), bold, italic, out);
      }
      last = m.end();
    }
    addPlain(text.substring(last), bold, italic, out);
  }

  private static void addPlain(String text, boolean bold, boolean italic, List<Span> out) {
    if (!text.isEmpty()) {
      out.add(new Span(text, bold, italic, false));
    }
  }
}
