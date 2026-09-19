#!/usr/bin/env python3
"""Generates synthetic 'photo of a curved book page' images to test MakeACopy's dewarp.

Flat page: numbered horizontal text lines + ruled lines + border, evenly spaced.
Warp: cylindrical bend (top/bottom edges curve downwards/upwards) + mild perspective,
placed on a dark 'desk' background. After a correct dewarp the lines must be
straight, horizontal and evenly spaced again.

Two variants are written:
  - dewarp_test.jpg            symmetric curl (bump peak centered, u=0.5)
  - dewarp_test_asymmetric.jpg off-center curl (peak shifted towards one side), to
    exercise the depth-slider's curve-handle tangential (left/right) adjustment.
"""
import math
from PIL import Image, ImageDraw, ImageFont

# --- 1) Flat page -----------------------------------------------------------
PW, PH = 1200, 1700
page = Image.new("RGB", (PW, PH), (250, 248, 242))
d = ImageDraw.Draw(page)
try:
    font = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 40)
    small = ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc", 28)
except Exception:
    font = small = ImageFont.load_default()

d.rectangle([8, 8, PW - 9, PH - 9], outline=(60, 60, 60), width=6)
N = 24
for i in range(N):
    y = int(80 + i * (PH - 160) / (N - 1))
    d.line([(60, y), (PW - 60, y)], fill=(150, 150, 160), width=3)
    d.text((70, y - 46), f"{i+1:02d}  Zeile {i+1} — gleichmaessiger Abstand, gerade Linie",
           fill=(20, 20, 30), font=font)
# vertical reference lines
for x in (300, 600, 900):
    d.line([(x, 60), (x, PH - 60)], fill=(200, 120, 120), width=2)
d.text((60, 16), "MakeACopy DEWARP-TESTSEITE", fill=(120, 30, 30), font=small)

# pad source page so out-of-range samples become background-colored
BG = (52, 48, 46)
padded = Image.new("RGB", (PW * 3, PH * 3), BG)
padded.paste(page, (PW, PH))

# --- 2) Warp onto photo canvas ----------------------------------------------
CW, CH = 1800, 2400
MX, MTOP = 250, 380      # margins of the page inside the photo
sag = 190                # cylindrical sagitta (both edges bow downwards)
persp = 60               # top narrower than bottom (mild perspective)


def bump(u, skew):
    """Smooth unit bump over u in [0, 1]: 0 at u=0 and u=1, peak 1 at an interior point that
    shifts with `skew` (0 = centered at u=0.5; positive shifts the peak earlier, negative later).

    Reparametrizes u before feeding it to sin(pi*u), the same trick as DewarpModel.blendWeight
    in the app (w = u + skew*u*(1-u)): a plain quadratic, so it stays infinitely differentiable
    everywhere. An earlier version stitched two quarter-sines at the peak instead, which has a
    slope discontinuity there — inv_point()'s Newton solver failed to converge right at that kink,
    producing a small staircase artifact in the rendered edge.
    """
    w = u + skew * u * (1 - u)
    return math.sin(math.pi * w)


def make_dst_point(skew_top, skew_bottom):
    def dst_point(u, v):
        """u,v in [0,1] on the page -> (x,y) in the photo."""
        half_top = (CW / 2 - MX) - persp
        half_bot = (CW / 2 - MX)
        half = half_top * (1 - v) + half_bot * v
        x = CW / 2 + (u - 0.5) * 2 * half
        y0 = MTOP + sag * bump(u, skew_top)             # curved top edge
        y1 = CH - MTOP + sag * 0.55 * bump(u, skew_bottom)  # curved bottom edge (less sag)
        y = y0 * (1 - v) + y1 * v
        return x, y

    return dst_point


# Inverse map via dense grid + bilinear inversion (pure python, coarse grid then per-pixel too slow;
# instead use PIL MESH: destination rectangles -> source quads). We need dst->src, so build
# a fine grid in *destination* space by inverting numerically per grid node.
def inv_point(dst_point, x, y, iters=12):
    u, v = 0.5, 0.5
    for _ in range(iters):
        X, Y = dst_point(u, v)
        # numeric jacobian
        e = 1e-4
        Xu, Yu = dst_point(min(u + e, 1), v); Xu = (Xu - X) / e; Yu = (Yu - Y) / e
        Xv, Yv = dst_point(u, min(v + e, 1)); Xv = (Xv - X) / e; Yv = (Yv - Y) / e
        det = Xu * Yv - Xv * Yu
        if abs(det) < 1e-9: break
        du = ((x - X) * Yv - (y - Y) * Xv) / det
        dv = ((y - Y) * Xu - (x - X) * Yu) / det
        u = u + du; v = v + dv
        u = max(-0.9, min(1.9, u)); v = max(-0.9, min(1.9, v))
    return u, v


def render(skew_top, skew_bottom, out_path):
    dst_point = make_dst_point(skew_top, skew_bottom)
    # Mesh cell size in px. A quad is entirely replaced by a solid-background fallback quad
    # when ANY of its 4 corners fails to invert (see the `ok` check below) — near the curved
    # top/bottom edges, where a row of cells can flip from fully-inside to fully-outside the
    # page over a short x range, a coarse grid turns that into a visible staircase notch
    # instead of a smooth edge. 6px keeps the residual within a pixel or two (invisible at
    # normal zoom) at the cost of a few more seconds of runtime.
    GS = 6
    mesh = []
    for gy in range(0, CH, GS):
        for gx in range(0, CW, GS):
            box = (gx, gy, min(gx + GS, CW), min(gy + GS, CH))
            quad = []
            ok = True
            for (x, y) in ((box[0], box[1]), (box[0], box[3]), (box[2], box[3]), (box[2], box[1])):
                u, v = inv_point(dst_point, x, y)
                X, Y = dst_point(u, v)
                if abs(X - x) > 2 or abs(Y - y) > 2:
                    ok = False  # inversion landed on a fold / outside the page
                quad.extend((u * PW, v * PH))
            if not ok:
                m = 0.6  # sample from padded background region
                quad = [-m * PW, -m * PH, -m * PW, -m * PH + 1, -m * PW + 1, -m * PH + 1, -m * PW + 1, -m * PH]
            mesh.append((box, tuple(quad)))

    mesh = [(box, tuple(c + (PW if i % 2 == 0 else PH) for i, c in enumerate(q))) for box, q in mesh]
    photo = padded.transform((CW, CH), Image.MESH, mesh, Image.BILINEAR)
    photo.save(out_path, quality=92)
    print("written:", out_path, photo.size)


render(0.0, 0.0, "dewarp_test.jpg")
# Off-center curl: peak shifted towards the left on top, right on bottom, so the top and
# bottom curve-handle's left/right adjustment can be tested independently.
render(0.6, -0.6, "dewarp_test_asymmetric.jpg")
