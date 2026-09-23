"""Synthetic test pages for GitHub issue #87 (paddle flavor).

Mimics the geometry of the reporter's samples with own text:
  1. lorem_columns  : three justified columns, line numbers glued to the first word
                      ("01Lorem ipsum"), wide justified gaps  -> latin rec misread + word splitter
  2. dropcap_footer : two-column article with headline, teaser, two-line drop cap,
                      left column ending level with the right column's footer -> line merge + band
  3. headline_split : three columns under a long bold headline with wide word gaps -> split headline
  4. big_glyphs     : huge "37 %" over three small caption lines -> detector fragments
  5. wide_gaps      : one wide justified column with three or four words per line -> the
                      recogniser drops the spaces, the word splitter must place them by position
"""
import os, random, re, sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(HERE, "fonts")
def font(name, size):
    for cand in [os.path.join(FONTS, "liberation-fonts-ttf-2.1.5", name), os.path.join(FONTS, name)]:
        if os.path.exists(cand):
            return ImageFont.truetype(cand, size)
    return ImageFont.truetype(name, size)

SERIF = "LiberationSerif-Regular.ttf"
SERIF_B = "LiberationSerif-Bold.ttf"
SERIF_I = "LiberationSerif-Italic.ttf"
SANS_B = "LiberationSans-Bold.ttf"
SANS = "LiberationSans-Regular.ttf"

LOREM = ("Lorem ipsum dolor sit amet, consectetur adipiscing elit. Vestibulum consequat mi quis "
         "pretium semper. Proin luctus orci ac neque venenatis, quis commodo dolor posuere. "
         "Curabitur dignissim sapien quis cursus egestas. Donec blandit auctor arcu, nec "
         "pellentesque eros molestie eget. In consectetur aliquam hendrerit. Sed cursus mauris "
         "vitae ligula pellentesque, non pellentesque urna aliquet. Fusce placerat mauris enim, "
         "nec rutrum purus semper vel. Praesent tincidunt neque eu pellentesque pharetra. Fusce "
         "pellentesque est orci. Integer sodales tincidunt tristique. Sed a metus posuere, "
         "adipiscing nunc et, viverra odio. Donec auctor molestie sem, sit amet tristique lectus "
         "hendrerit sed. Cras sodales nisl sed orci mattis iaculis. Nunc eget dolor accumsan, "
         "pharetra risus a, vestibulum mauris. Nunc vulputate lobortis mollis. Vivamus nec tellus "
         "faucibus, tempor magna nec, facilisis felis. Donec commodo enim a vehicula pellentesque. "
         "Nullam vehicula vestibulum est vel ultricies. Aliquam velit massa, laoreet vel leo nec, "
         "volutpat facilisis eros. Donec consequat arcu ut diam tempor luctus. Cum sociis natoque "
         "penatibus et magnis dis parturient montes, nascetur ridiculus mus. Praesent vitae lacus "
         "vel leo sodales pharetra a a nibh. Vestibulum ante ipsum primis in faucibus orci luctus.")

# Own German dummy article (no third-party text).
GERMAN_1 = ("Er hörte leise Schritte hinter sich. Das bedeutete nichts Gutes. Wer würde ihm schon "
            "folgen, spät in der Nacht und dazu noch in dieser engen Gasse mitten im übel "
            "beleumundeten Hafenviertel? Gerade jetzt, wo er das Ding seines Lebens gedreht hatte "
            "und mit der Beute verschwinden wollte! Hatte einer seiner zahllosen Kollegen dieselbe "
            "Idee gehabt, ihn beobachtet und abgewartet, um ihn nun um die Früchte seiner Arbeit zu "
            "erleichtern?")
GERMAN_2 = ("Oder gehörten die Schritte hinter ihm zu einem der unzähligen Gesetzeshüter dieser "
            "Stadt, und die stählerne Acht um seine Handgelenke würde gleich zuschnappen? Er konnte "
            "die Aufforderung stehen zu bleiben schon hören. Gehetzt sah er sich um. Plötzlich "
            "erblickte er den schmalen Durchgang. Blitzartig drehte er sich nach rechts und "
            "verschwand zwischen den beiden Gebäuden. Beinahe wäre er dabei über den umgestürzten "
            "Mülleimer gefallen, der mitten im Weg lag. Er versuchte, sich in der Dunkelheit seinen "
            "Weg zu ertasten und erstarrte: Anscheinend gab es keinen anderen Ausweg aus diesem "
            "kleinen Hof als den Durchgang, durch den er gekommen war. Die Schritte wurden lauter "
            "und lauter, er sah eine dunkle Gestalt um die Ecke biegen. Fieberhaft irrten seine "
            "Augen durch die nächtliche Dunkelheit und suchten einen Ausweg.")

ARTICLE = ("Es war ein ruhiger Abend im Hafen, als die alte Fähre zum letzten Mal ablegte. Seit "
           "vierzig Jahren hatte sie die kleine Insel mit dem Festland verbunden, bei Sturm und bei "
           "Flaute, im Sommer voller Ausflügler und im Winter fast leer. Nun sollte ein neues Schiff "
           "ihren Dienst übernehmen, schneller, größer und mit einem Elektroantrieb, der leiser ist "
           "als das alte Dieselaggregat. Viele Inselbewohner standen am Kai und winkten, einige "
           "hatten Tränen in den Augen. Der Kapitän, der die Fähre seit zwei Jahrzehnten gesteuert "
           "hatte, ließ das Horn dreimal ertönen. Dann verschwand das Schiff langsam im Abendlicht. "
           "Was aus der alten Fähre wird, ist noch nicht entschieden. Ein Verein möchte sie als "
           "Museumsschiff erhalten, die Reederei denkt über einen Verkauf nach. Die Gemeinde will "
           "in den kommenden Wochen mit allen Beteiligten sprechen und eine Lösung finden, die der "
           "Geschichte des Schiffes gerecht wird. Bis dahin liegt die Fähre im alten Hafenbecken, "
           "wo sie schon vor vierzig Jahren zum ersten Mal festgemacht hatte. Für die Bewohner der "
           "Insel bleibt sie ein Stück Heimat, das man nicht so leicht ersetzt.")
ARTICLE_2 = ("Die neue Fähre bietet Platz für dreihundert Fahrgäste und zwanzig Fahrzeuge. Sie "
             "braucht für die Überfahrt nur noch fünfundzwanzig Minuten statt vierzig. An Bord "
             "gibt es einen Aufenthaltsraum mit großen Fenstern, eine kleine Küche und Platz für "
             "Fahrräder. Der Fahrplan bleibt vorerst unverändert, im Sommer sollen zusätzliche "
             "Abendfahrten angeboten werden. Die Reederei rechnet damit, dass die Zahl der "
             "Fahrgäste in den ersten Jahren deutlich steigen wird. Kritiker bemängeln, dass die "
             "Fahrpreise zum Fahrplanwechsel angehoben werden. Die Gemeinde verhandelt noch über "
             "einen Nachlass für Inselbewohner und Schulkinder.")

GT = []  # drawn text lines in drawing order (per page, reset in main)

def gt_line(text):
    GT.append(text)

def wrap(words, f, width, draw):
    lines, cur = [], []
    for w in words:
        test = " ".join(cur + [w])
        if draw.textlength(test, font=f) <= width or not cur:
            cur.append(w)
        else:
            lines.append(cur); cur = [w]
    if cur: lines.append(cur)
    return lines

def draw_justified(draw, x, y, width, words, f, pitch, justify_last=False, prefix_numbers=False):
    """Draws justified lines; returns y after the last line and the number of lines."""
    lines = wrap(words, f, width, draw)
    n = 0
    for i, lw in enumerate(lines):
        if prefix_numbers and i < 10:
            lw = [f"{i+1:02d}{lw[0]}"] + lw[1:]
        last = (i == len(lines) - 1)
        gt_line(" ".join(lw))
        if last and not justify_last or len(lw) == 1:
            draw.text((x, y), " ".join(lw), font=f, fill=0)
        else:
            total = sum(draw.textlength(w, font=f) for w in lw)
            gap = (width - total) / (len(lw) - 1)
            cx = x
            for w in lw:
                draw.text((cx, y), w, font=f, fill=0)
                cx += draw.textlength(w, font=f) + gap
        y += pitch; n += 1
    return y, n

def page(w, h):
    return Image.new("L", (w, h), 255)

def lorem_columns(out):
    # geometry like the reporter's page: 3470x2480, 3 columns ~845 px, pitch 59 px, font 49 px
    W, H = 3470, 2480
    im = page(W, H); d = ImageDraw.Draw(im)
    f = font(SERIF, 49); pitch = 59
    cols = [(214, 845), (1185, 890), (2233, 820)]
    y0 = 250
    y, _ = draw_justified(d, cols[0][0], y0, cols[0][1], LOREM.split(), f, pitch, prefix_numbers=True)
    # column 2: german text, paragraph, bold heading, second paragraph with indent
    y2, _ = draw_justified(d, cols[1][0], y0, cols[1][1], GERMAN_1.split(), f, pitch)
    fb = font(SERIF_B, 56)
    d.text((cols[1][0], y2 + 20), "Überschrift Dummy", font=fb, fill=0)
    y2 += 20 + 90
    words = GERMAN_2.split()
    first = wrap(words, f, cols[1][1] - 60, d)[0]
    draw_justified(d, cols[1][0] + 60, y2, cols[1][1] - 60, first, f, pitch, justify_last=True)
    draw_justified(d, cols[1][0], y2 + pitch, cols[1][1], words[len(first):], f, pitch)
    # column 3: counting words
    y3 = y0
    for word, cnt in [("one", 10), ("two", 10), ("three", 8), ("fore", 3), ("five", 10)]:
        t = word.capitalize() + " " + " ".join([word] * (cnt - 1))
        gt_line(t)
        d.text((cols[2][0], y3), t, font=f, fill=0)
        y3 += pitch
    im.save(out)

def dropcap_footer(out):
    # like "Der Untergang": 2302x3465, two columns ~1020 px, body pitch 75, font 44
    W, H = 2302, 3465
    im = page(W, H); d = ImageDraw.Draw(im)
    fh = font(SANS_B, 230)
    gt_line("Die Fähre")
    d.text((80, 40), "Die Fähre", font=fh, fill=0)
    ft = font(SERIF_I, 62)
    fb = font(SERIF, 44); pitch = 75
    left, right, cw = 110, 1210, 1020
    top = 380
    # right column: long text, author, rule, footer
    right_words = (ARTICLE_2 + " " + GERMAN_2).split()
    yr, _ = draw_justified(d, right, top, cw, right_words, fb, pitch)
    yr += pitch
    fa = font(SERIF_B, 44)
    author = "Martina Musterfrau/abc"
    gt_line(author)
    d.text((right + cw - int(d.textlength(author, font=fa)), yr), author, font=fa, fill=0)
    yr += pitch + 30
    d.line([(right, yr), (right + cw, yr)], fill=0, width=3)
    footer_y = yr + 30
    footer = "Hafenzeitung, Sonntag, 19.30 Uhr"
    # left column: teaser, drop cap, body filled exactly down to the footer line
    y = top
    teaser = ("Nach vierzig Jahren geht die alte Inselfähre in den Ruhestand. Ein neues Schiff "
              "übernimmt den Dienst. Was mit der alten wird, ist offen").split()
    for lw in wrap(teaser, ft, cw - 40, d):
        gt_line(" ".join(lw))
        d.text((left, y), " ".join(lw), font=ft, fill=0); y += 96
    y += 60
    # align the body grid so that a body line lands exactly on footer_y
    n_lines = (footer_y - y) // pitch
    y = footer_y - n_lines * pitch
    fd = font(SERIF, 150)
    d.text((left, y - 18), "E", font=fd, fill=0)
    gt_line("E")
    dc_w = int(d.textlength("E", font=fd)) + 16
    words = (ARTICLE + " " + ARTICLE_2 + " " + GERMAN_1).split()
    words = ["s"] + words[1:]
    first_two = wrap(words, fb, cw - dc_w, d)
    y1, _ = draw_justified(d, left + dc_w, y, cw - dc_w, first_two[0] + first_two[1], fb, pitch, justify_last=True)
    consumed = len(first_two[0]) + len(first_two[1])
    remaining_lines = n_lines + 1 - 2
    lines = wrap(words[consumed:], fb, cw, d)[:remaining_lines]
    flat = [w for lw in lines for w in lw]
    draw_justified(d, left, y1, cw, flat, fb, pitch, justify_last=True)
    gt_line(footer)
    d.text((right, footer_y), footer, font=ft, fill=0)
    im.save(out)

def headline_split(out):
    # like the Excel page: 2509x2512, bold sans headline with wide gaps over three columns
    W, H = 2509, 2512
    im = page(W, H); d = ImageDraw.Draw(im)
    fh = font(SANS_B, 96)
    headline = "Fähre: Welche Fahrten fallen in dieser Woche aus?"
    # justify headline over 0.92 of the width with generous word gaps
    hw = int(W * 0.92); x0 = 100
    words = headline.split()
    gt_line(headline)
    total = sum(d.textlength(w, font=fh) for w in words)
    gap = (hw - total) / (len(words) - 1)
    cx = x0
    for w in words:
        d.text((cx, 90), w, font=fh, fill=0); cx += d.textlength(w, font=fh) + gap
    d.line([(x0, 230), (x0 + hw, 230)], fill=120, width=2)
    fs = font(SANS, 44); pitch = 66
    cols = [(100, 760), (960, 760), (1820, 600)]
    draw_justified(d, cols[0][0], 330, cols[0][1], ARTICLE.split(), fs, pitch)
    y2, _ = draw_justified(d, cols[1][0], 330, cols[1][1], ARTICLE_2.split()[:60], fs, pitch)
    fc = font(SANS, 40)
    cap = ("Der Fahrplan zum Vergleich der Spalten A und B kann auf weitere Wochen erweitert "
           "werden.").split()
    draw_justified(d, cols[2][0], 330, 380, cap, fc, 60)
    im.save(out)

def wide_gaps(out):
    # One 1400 px column, 3-4 words per justified line: gaps of several word widths. Line
    # numbers glued to the first word as on the reporter's page ("01Lorem").
    W, H = 2000, 2480
    im = page(W, H); d = ImageDraw.Draw(im)
    f = font(SERIF, 49); pitch = 72
    words = (GERMAN_2 + " " + LOREM).split()
    x, y, cw = 300, 250, 1400
    i = 0
    n = 0
    while i < len(words) and y < H - 200:
        k = 3 + (n % 2)  # alternate 3 and 4 words per line
        lw = words[i:i + k]
        if n < 10:
            lw = [f"{n+1:02d}{lw[0]}"] + lw[1:]
        gt_line(" ".join(lw))
        total = sum(d.textlength(w, font=f) for w in lw)
        gap = (cw - total) / (len(lw) - 1)
        cx = x
        for w in lw:
            d.text((cx, y), w, font=f, fill=0)
            cx += d.textlength(w, font=f) + gap
        i += k
        y += pitch
        n += 1
    im.save(out)

def big_glyphs(out):
    # like the "37 %" sample: 795x587 photo-ish crop, huge digits over three caption lines
    W, H = 795, 587
    im = Image.new("RGB", (W, H), (246, 240, 226)); d = ImageDraw.Draw(im)
    fb = font(SANS_B, 330)
    d.text((40, -40), "37 %", font=fb, fill=(40, 90, 190))
    fc = font(SANS_B, 40)
    gt_line("37 %")
    for i, t in enumerate(["ALLER PENDLER", "NEHMEN DIE FRÜHE FÄHRE", "ZUR ARBEIT"]):
        gt_line(re.sub("  +", " ", t))
        tw = d.textlength(t, font=fc)
        d.text(((W - tw) / 2, 372 + i * 68), t, font=fc, fill=(40, 90, 190))
    im.save(out)

if __name__ == "__main__":
    outdir = sys.argv[1] if len(sys.argv) > 1 else HERE
    os.makedirs(outdir, exist_ok=True)
    for fn, name in [(lorem_columns, "synth_lorem_columns"), (dropcap_footer, "synth_dropcap_footer"),
                     (headline_split, "synth_headline_split"), (big_glyphs, "synth_big_glyphs"),
                     (wide_gaps, "synth_wide_gaps")]:
        GT.clear()
        fn(os.path.join(outdir, name + ".png"))
        with open(os.path.join(outdir, name + ".gt.txt"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(GT) + "\n")
        print(name, "lines", len(GT))
