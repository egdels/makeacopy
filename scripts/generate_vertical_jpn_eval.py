#!/usr/bin/env python3
"""Generates Japanese eval assets for issue #88 (vertical CJK layout).

Renders the sample text from the issue report in both vertical layout
(columns top-to-bottom, right-to-left) and horizontal layout, using the
bundled Noto Sans CJK font. Output goes to
app/src/androidTestPaddle/assets/eval/ as JPEG + ground-truth text files.

Vertical typesetting details reproduced here:
 - Ideographic punctuation (、 。) sits in the top-right quadrant of its cell.
 - Small kana (っ ょ ゃ ゅ ッ ョ ャ ュ ...) are shifted towards the top-right.
 - Prolonged sound mark (ー) is rotated 90 degrees in vertical text.

Usage: python3 scripts/generate_vertical_jpn_eval.py
"""

import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app/src/full/assets/fonts/NotoSansCJKsc-Regular.ttf")
FONT_KANA = os.path.join(ROOT, "app/src/full/assets/fonts/NotoSansJP-Kana.ttf")
OUT = os.path.join(ROOT, "app/src/androidTestPaddle/assets/eval/jpn")

LINES = [
    "すると、１億円を受け取った親は家に帰らなくなった。",
    "１億円で遊んでいるのだろう。",
    "なくなったらたかりに来ると思ったので、親に内緒で高級マンションに引っ越すことにした。",
]

PUNCT = set("、。")
SMALL_KANA = set("ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮ")
ROTATED = set("ー〜…（）「」")

FONT_SIZE = 44
CELL = 64  # vertical cell (both column width and glyph advance)
MARGIN = 80


MAX_COL_CHARS = 20  # book-like page: long sentences wrap over multiple columns


def load_fonts():
    return ImageFont.truetype(FONT, FONT_SIZE), ImageFont.truetype(FONT_KANA, FONT_SIZE)


def pick_font(ch, main, kana):
    # the bundled CJKsc font is a subset without kana; fall back per glyph
    for f in (main, kana):
        mask = f.getmask(ch)
        if mask.getbbox() is not None:
            return f
    return main


def gen_vertical(path):
    main, kana = load_fonts()
    # wrap each logical line into columns of at most MAX_COL_CHARS chars
    columns = []
    for line in LINES:
        for i in range(0, len(line), MAX_COL_CHARS):
            columns.append(line[i : i + MAX_COL_CHARS])
    max_chars = max(len(c) for c in columns)
    w = MARGIN * 2 + CELL * len(columns)
    h = MARGIN * 2 + CELL * max_chars
    img = Image.new("L", (w, h), 255)
    d = ImageDraw.Draw(img)
    # columns right-to-left
    for col, line in enumerate(columns):
        x0 = w - MARGIN - CELL * (col + 1)
        for row, ch in enumerate(line):
            y0 = MARGIN + CELL * row
            font = pick_font(ch, main, kana)
            bbox = d.textbbox((0, 0), ch, font=font)
            gw, gh = bbox[2] - bbox[0], bbox[3] - bbox[1]
            if ch in PUNCT:
                # top-right quadrant of the cell
                x = x0 + CELL - gw - bbox[0] - 6
                y = y0 - bbox[1] + 2
                d.text((x, y), ch, font=font, fill=0)
            elif ch in ROTATED:
                glyph = Image.new("L", (CELL, CELL), 255)
                gd = ImageDraw.Draw(glyph)
                gd.text(
                    ((CELL - gw) / 2 - bbox[0], (CELL - gh) / 2 - bbox[1]),
                    ch,
                    font=font,
                    fill=0,
                )
                glyph = glyph.rotate(-90)
                img.paste(glyph, (x0, y0))
            else:
                dx, dy = 0, 0
                if ch in SMALL_KANA:
                    dx, dy = 6, -4  # small kana lean to the top-right
                x = x0 + (CELL - gw) / 2 - bbox[0] + dx
                y = y0 + (CELL - gh) / 2 - bbox[1] + dy
                d.text((x, y), ch, font=font, fill=0)
    img.convert("RGB").save(path, quality=92)
    print("wrote", path, img.size)


def gen_horizontal(path):
    main, kana = load_fonts()
    line_h = CELL
    max_len = max(len(l) for l in LINES)
    w = MARGIN * 2 + FONT_SIZE * max_len + 40
    h = MARGIN * 2 + line_h * len(LINES)
    img = Image.new("L", (w, h), 255)
    d = ImageDraw.Draw(img)
    for i, line in enumerate(LINES):
        x = MARGIN
        y = MARGIN + i * line_h
        for ch in line:
            font = pick_font(ch, main, kana)
            d.text((x, y), ch, font=font, fill=0)
            x += d.textlength(ch, font=font)
    img.convert("RGB").save(path, quality=92)
    print("wrote", path, img.size)


def main():
    os.makedirs(OUT, exist_ok=True)
    gen_vertical(os.path.join(OUT, "vertical_jpn.jpg"))
    gen_horizontal(os.path.join(OUT, "horizontal_jpn.jpg"))
    gt = "\n".join(LINES) + "\n"
    for name in ("vertical_jpn.gt.txt", "horizontal_jpn.gt.txt"):
        with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
            f.write(gt)
    print("wrote ground truth files")


if __name__ == "__main__":
    main()
