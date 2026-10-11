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
 * The help and the privacy policy (HELP*.md in help/, docs/_includes/privacy.md), parsed into
 * blocks of styled spans that DocumentActivity renders as TextViews. Only the subset of Markdown
 * those files use: a "#" title, "##" headings, paragraphs, "-" bullets with indented continuation
 * lines, inline {@code `code`}, {@code **bold**} and {@code *italic*}, and addresses in angle
 * brackets ({@code <https://...>}, {@code <name@example.org>}), which the web page turns into
 * links. The files deliberately use no tables or link syntax so that this stays small.
 */
public record MarkdownDocument(String title, List<Block> blocks) {

  public enum BlockType {
    HEADING,
    PARAGRAPH,
    BULLET
  }

  public record Block(BlockType type, List<Span> spans) {}

  /** A run of text with one style; {@code code} text is shown in a monospace font. */
  public record Span(String text, boolean bold, boolean italic, boolean code) {}

  private static final Pattern TRAILING_SPACE = Pattern.compile("\\s+$");

  private static final Pattern INLINE =
      Pattern.compile(
          "`([^`]+)`|\\*\\*([^*]+)\\*\\*|\\*([^*]+)\\*|<((?:https://|[^\\s<>@]+@)[^\\s<>]+)>");

  /** {@code fallbackTitle} is used when the text has no "#" line. */
  public static MarkdownDocument parse(String markdown, String fallbackTitle) {
    Parser parser = new Parser();
    String title = fallbackTitle;
    for (String raw : markdown.split("\\r?\\n", -1)) {
      String line = TRAILING_SPACE.matcher(raw).replaceFirst("");
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
    return new MarkdownDocument(title, Collections.unmodifiableList(parser.blocks));
  }

  private static final class Parser {
    final List<Block> blocks = new ArrayList<>();
    final StringBuilder paragraph = new StringBuilder();
    StringBuilder bullet;

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
