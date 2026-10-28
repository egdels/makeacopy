#!/usr/bin/env python3
# label_corners.py
#
# Practical OpenCV-based corner labeler with:
# - OpenCV corner proposal (baseline)
# - Robust mouse handling even when the window is resized (maps display -> original coords)
# - Stable label keys using relative paths (avoids basename collisions)
# - Consistent corner order (0..3 = TL,TR,BR,BL clockwise) enforced on save
# - Fast workflow: a = save+next, s = save only, n/p = next/prev, r = re-propose, q = quit
#
# Usage:
#   python label_corners.py --img_dir /path/to/images --out labels.jsonl
#
# Notes:
# - Labels are saved as JSONL: {"image": "<relative/path>", "corners": [[x,y],...]}
# - Corners are in ORIGINAL image pixel coords (not resized).

import os
import json
import argparse

import cv2
import numpy as np


# ----------------------------
# OpenCV corner proposal (simple baseline)
# Replace with your OpenCVUtils-like pipeline later if desired.
# ----------------------------
def propose_corners(img_bgr: np.ndarray) -> np.ndarray:
    h, w = img_bgr.shape[:2]
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)
    gray = cv2.GaussianBlur(gray, (5, 5), 0)

    thr = cv2.adaptiveThreshold(
        gray, 255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY,
        31, 10
    )
    thr = 255 - thr  # invert: document edges often become white-ish
    thr = cv2.morphologyEx(thr, cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8), iterations=1)
    edges = cv2.Canny(thr, 50, 150)

    cnts, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not cnts:
        return np.array([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]], np.float32)

    cnts = sorted(cnts, key=cv2.contourArea, reverse=True)
    for c in cnts[:8]:
        peri = cv2.arcLength(c, True)
        approx = cv2.approxPolyDP(c, 0.02 * peri, True)
        if len(approx) == 4 and cv2.isContourConvex(approx):
            pts = approx.reshape(-1, 2).astype(np.float32)
            return sort_corners_clockwise(pts)

    rect = cv2.minAreaRect(cnts[0])
    pts = cv2.boxPoints(rect).astype(np.float32)
    return sort_corners_clockwise(pts)


def sort_corners_clockwise(pts: np.ndarray) -> np.ndarray:
    """
    Returns corners in clockwise order starting at top-left:
      0=TL, 1=TR, 2=BR, 3=BL
    
    This matches the expected order in MakeACopy/ImageOps.java.
    """
    pts = np.asarray(pts, dtype=np.float32).reshape(4, 2)
    
    # Step 1: find top-left (smallest x+y sum)
    s = pts[:, 0] + pts[:, 1]
    tl_idx = int(np.argmin(s))
    
    # Step 2: find bottom-right (largest x+y sum)
    br_idx = int(np.argmax(s))
    
    # Step 3: remaining two points are TR and BL
    # TR has larger x, BL has larger y
    remaining = [i for i in range(4) if i not in [tl_idx, br_idx]]
    
    if pts[remaining[0], 0] > pts[remaining[1], 0]:
        tr_idx, bl_idx = remaining[0], remaining[1]
    else:
        tr_idx, bl_idx = remaining[1], remaining[0]
    
    # Order: TL(0), TR(1), BR(2), BL(3) clockwise
    return pts[[tl_idx, tr_idx, br_idx, bl_idx]]


def polygon_signed_area(pts: np.ndarray) -> float:
    x = pts[:, 0]
    y = pts[:, 1]
    return 0.5 * float(np.sum(x * np.roll(y, -1) - y * np.roll(x, -1)))


# ----------------------------
# Display scaling (robust mouse mapping)
# ----------------------------
def compute_display_size(
    img_w: int,
    img_h: int,
    max_side: int,
    allow_upscale: bool = False,
) -> tuple[int, int, float, float]:
    """
    Returns (disp_w, disp_h, sx, sy) where:
      disp = resized for display
      sx = img_w / disp_w, sy = img_h / disp_h  (multiply display coords by sx/sy to get original)
    """
    if max_side <= 0:
        return img_w, img_h, 1.0, 1.0
    scale = max_side / float(max(img_w, img_h))
    if not allow_upscale:
        scale = min(scale, 1.0)  # never upscale
    disp_w = max(1, int(round(img_w * scale)))
    disp_h = max(1, int(round(img_h * scale)))
    sx = img_w / float(disp_w)
    sy = img_h / float(disp_h)
    return disp_w, disp_h, sx, sy


def compute_display_size_fit_window(
    img_w: int,
    img_h: int,
    win_w: int,
    win_h: int,
    allow_upscale: bool = True,
) -> tuple[int, int, float, float]:
    """Fit image into current window client size while preserving aspect ratio."""
    if win_w <= 0 or win_h <= 0:
        return img_w, img_h, 1.0, 1.0

    scale_w = win_w / float(img_w)
    scale_h = win_h / float(img_h)
    scale = min(scale_w, scale_h)
    if not allow_upscale:
        scale = min(scale, 1.0)

    disp_w = max(1, int(round(img_w * scale)))
    disp_h = max(1, int(round(img_h * scale)))
    sx = img_w / float(disp_w)
    sy = img_h / float(disp_h)
    return disp_w, disp_h, sx, sy


# ----------------------------
# UI
# ----------------------------
class Labeler:
    def __init__(self, img_dir: str, paths: list[str], out_path: str, max_side: int = 1400):
        self.img_dir = os.path.abspath(img_dir)
        self.paths = paths
        self.out_path = out_path
        self.labels = self.load_existing(out_path)
        self.i = 0

        self.drag_idx = None
        self.radius = 12

        # display scaling
        self.max_side = max_side
        self.fit_to_window = True
        self.allow_upscale = True
        self._win_name: str | None = None
        self._sx = 1.0
        self._sy = 1.0
        self._disp_w = None
        self._disp_h = None

        # HUD bar (drawn above the image, so text is always readable)
        self.hud_bar_h = 95
        self._hud_bar_h = 0

    def load_existing(self, out_path: str) -> dict:
        # image -> {corners: [[x,y],...], optional: score/mask_mean/mask_max/fg_mean/fg_frac}
        labels: dict[str, dict] = {}
        
        # Check if out_path is a JSONL file
        if os.path.exists(out_path) and os.path.isfile(out_path):
            with open(out_path, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    obj = json.loads(line)
                    # Support both JSONL format ("corners") and trainable format ("corners_px")
                    corners = obj.get("corners") or obj.get("corners_px")
                    if corners is None:
                        continue
                    entry = {"corners": corners}
                    for k in ("score", "mask_mean", "mask_max", "fg_mean", "fg_frac"):
                        if k in obj:
                            entry[k] = obj[k]
                    labels[obj["image"]] = entry
        
        # Also check for trainable format: look for labels/ directory next to images/
        # If img_dir ends with /images, check for sibling /labels directory
        labels_dir = None
        if hasattr(self, 'img_dir'):
            img_dir_abs = os.path.abspath(self.img_dir)
            if img_dir_abs.endswith("/images") or img_dir_abs.endswith("\\images"):
                parent = os.path.dirname(img_dir_abs)
                candidate = os.path.join(parent, "labels")
                if os.path.isdir(candidate):
                    labels_dir = candidate
        
        if labels_dir:
            for json_file in sorted(os.listdir(labels_dir)):
                if not json_file.endswith(".json"):
                    continue
                json_path = os.path.join(labels_dir, json_file)
                try:
                    with open(json_path, "r", encoding="utf-8") as f:
                        obj = json.load(f)
                    corners = obj.get("corners") or obj.get("corners_px")
                    if corners is None:
                        continue
                    image_name = obj.get("image", json_file.replace(".json", ".png"))
                    entry = {"corners": corners}
                    for k in ("score", "mask_mean", "mask_max", "fg_mean", "fg_frac"):
                        if k in obj:
                            entry[k] = obj[k]
                    labels[image_name] = entry
                except (json.JSONDecodeError, IOError):
                    continue
        
        return labels

    def save_all(self):
        os.makedirs(os.path.dirname(os.path.abspath(self.out_path)) or ".", exist_ok=True)
        with open(self.out_path, "w", encoding="utf-8") as f:
            for k in sorted(self.labels.keys()):
                entry = self.labels[k]
                obj = {
                    "image": k,
                    "corners": entry["corners"],
                }
                for key in ("score", "mask_mean", "mask_max", "fg_mean", "fg_frac"):
                    if key in entry and entry[key] is not None:
                        obj[key] = entry[key]
                f.write(json.dumps(obj, ensure_ascii=False) + "\n")

    def save_one(self, rel_name: str, corners: np.ndarray):
        corners = sort_corners_clockwise(corners)  # enforce consistent label order
        prev = self.labels.get(rel_name)
        if isinstance(prev, dict):
            entry = dict(prev)
            entry["corners"] = corners.tolist()
        else:
            entry = {"corners": corners.tolist()}
        self.labels[rel_name] = entry
        self.save_all()
        score_str = ""
        if "score" in entry and entry["score"] is not None:
            try:
                score_str = f" | score={float(entry['score']):.4f}"
            except (TypeError, ValueError):
                pass
        print(f"[saved] {rel_name}{score_str}")

    def current_path(self) -> str:
        return self.paths[self.i]

    def current_name(self) -> str:
        # stable key (relative path) to avoid collisions
        p = os.path.abspath(self.current_path())
        return os.path.relpath(p, self.img_dir).replace("\\", "/")

    def current_image(self) -> np.ndarray:
        p = self.current_path()
        img = cv2.imread(p, cv2.IMREAD_COLOR)  # BGR
        if img is None:
            raise FileNotFoundError(p)
        return img

    def get_corners(self, img: np.ndarray, rel_name: str) -> np.ndarray:
        if rel_name in self.labels:
            entry = self.labels[rel_name]
            if isinstance(entry, dict) and "corners" in entry:
                return np.array(entry["corners"], np.float32)
            return np.array(entry, np.float32)
        return propose_corners(img)

    def _current_score_text(self, rel_name: str) -> str:
        entry = self.labels.get(rel_name)
        if not isinstance(entry, dict):
            return ""
        if "score" not in entry or entry["score"] is None:
            return ""
        try:
            return f"score={float(entry['score']):.4f}"
        except (TypeError, ValueError):
            return ""

    @staticmethod
    def _truncate_text_to_width(
        text: str,
        max_width_px: int,
        font_face: int,
        font_scale: float,
        thickness: int,
    ) -> str:
        """Truncate with ellipsis so the rendered text fits into max_width_px."""
        if max_width_px <= 0:
            return ""
        (w, _h), _ = cv2.getTextSize(text, font_face, font_scale, thickness)
        if w <= max_width_px:
            return text
        ell = "..."
        (w_ell, _h2), _ = cv2.getTextSize(ell, font_face, font_scale, thickness)
        if w_ell >= max_width_px:
            return ""
        # binary-ish truncation (simple loop is fine for typical path lengths)
        lo = 0
        hi = len(text)
        best = ""
        while lo <= hi:
            mid = (lo + hi) // 2
            candidate = text[:mid] + ell
            (w_c, _), _ = cv2.getTextSize(candidate, font_face, font_scale, thickness)
            if w_c <= max_width_px:
                best = candidate
                lo = mid + 1
            else:
                hi = mid - 1
        return best

    def draw(self, img: np.ndarray, corners: np.ndarray, rel_name: str) -> np.ndarray:
        # Draw geometry on original image, then resize for display.
        out_orig = img.copy()
        corners_i = corners.astype(np.int32)

        # polygon
        cv2.polylines(out_orig, [corners_i], True, (0, 255, 0), 2, cv2.LINE_AA)

        # points
        for idx, (x, y) in enumerate(corners):
            cv2.circle(out_orig, (int(x), int(y)), self.radius, (0, 0, 255), -1, cv2.LINE_AA)
            cv2.putText(
                out_orig, str(idx), (int(x) + 10, int(y) - 10),
                cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2, cv2.LINE_AA
            )

        # resize for display and update mapping factors
        h, w = out_orig.shape[:2]

        disp_w = w
        disp_h = h
        sx = 1.0
        sy = 1.0

        # Prefer fitting to the current window size (fullscreen-friendly), fallback to max_side.
        # Note: we reserve a top HUD bar, so fit-to-window uses the remaining height.
        hud_bar_h = int(self.hud_bar_h)
        if self.fit_to_window and self._win_name is not None:
            try:
                _x, _y, win_w, win_h = cv2.getWindowImageRect(self._win_name)
                avail_h = max(1, int(win_h) - hud_bar_h)
                disp_w, disp_h, sx, sy = compute_display_size_fit_window(
                    w, h, win_w, avail_h, allow_upscale=self.allow_upscale
                )
            except Exception:
                disp_w, disp_h, sx, sy = compute_display_size(
                    w, h, self.max_side, allow_upscale=self.allow_upscale
                )
        else:
            disp_w, disp_h, sx, sy = compute_display_size(
                w, h, self.max_side, allow_upscale=self.allow_upscale
            )
        self._sx, self._sy = sx, sy
        self._disp_w, self._disp_h = disp_w, disp_h

        out_disp = out_orig
        if (disp_w, disp_h) != (w, h):
            interp = cv2.INTER_AREA if (disp_w < w or disp_h < h) else cv2.INTER_LINEAR
            out_disp = cv2.resize(out_orig, (disp_w, disp_h), interpolation=interp)

        # Compose final image with a top HUD bar (always readable, no overlap with image content)
        bar_h = max(0, int(hud_bar_h))
        self._hud_bar_h = bar_h
        out_final = out_disp
        if bar_h > 0:
            canvas = np.zeros((bar_h + out_disp.shape[0], out_disp.shape[1], 3), dtype=np.uint8)
            canvas[bar_h:bar_h + out_disp.shape[0], 0:out_disp.shape[1]] = out_disp
            out_final = canvas

        # HUD (draw on the bar so it stays visible even when image is resized/fitted)
        font = cv2.FONT_HERSHEY_SIMPLEX
        hud_scale = 0.85
        hud_thick = 2

        score_txt = self._current_score_text(rel_name)
        line1 = f"{self.i + 1}/{len(self.paths)}"
        if score_txt:
            line1 += f"  |  {score_txt}"

        max_w = int(out_final.shape[1] - 20)
        line2 = self._truncate_text_to_width(rel_name, max_w, font, 0.6, 2)

        y1 = 30
        y2 = 55
        y3 = 80
        if bar_h > 0:
            y1 = min(y1, bar_h - 65)
            y2 = min(y2, bar_h - 40)
            y3 = min(y3, bar_h - 15)

        cv2.putText(out_final, line1, (10, max(18, y1)), font, hud_scale, (255, 255, 255), hud_thick, cv2.LINE_AA)
        if line2:
            cv2.putText(out_final, line2, (10, max(18, y2)), font, 0.6, (255, 255, 255), 2, cv2.LINE_AA)

        cv2.putText(
            out_final,
            "drag points | a=save+next | s=save | r=re-propose | c=center | n/p=next/prev | f=fullscreen | z=fit | q=quit",
            (10, max(18, y3)), font, 0.55, (255, 255, 255), 2, cv2.LINE_AA
        )

        return out_final

    def hit_test(self, corners: np.ndarray, x: float, y: float) -> int | None:
        for idx, (cx, cy) in enumerate(corners):
            if (cx - x) ** 2 + (cy - y) ** 2 <= (self.radius * 1.6) ** 2:
                return idx
        return None

    def _disp_to_img(self, x_disp: int, y_disp: int) -> tuple[float, float]:
        # map display coords to original image coords
        y = float(y_disp) - float(self._hud_bar_h or 0)
        if y < 0:
            y = 0.0
        return float(x_disp) * self._sx, y * self._sy

    def run(self):
        win = "labeler"
        cv2.namedWindow(win, cv2.WINDOW_NORMAL)
        self._win_name = win

        # Start in fullscreen so that the entire image is visible.
        # (May be ignored depending on OS/window manager.)
        is_fullscreen = True
        try:
            cv2.setWindowProperty(win, cv2.WND_PROP_FULLSCREEN, cv2.WINDOW_FULLSCREEN)
        except Exception:
            is_fullscreen = False

        # state for mouse callback
        state = {"img": None, "corners": None, "name": None}

        def on_mouse(event, x, y, flags, param):
            if state["corners"] is None or state["img"] is None:
                return

            ix, iy = self._disp_to_img(x, y)

            if event == cv2.EVENT_LBUTTONDOWN:
                idx = self.hit_test(state["corners"], ix, iy)
                self.drag_idx = idx

            elif event == cv2.EVENT_LBUTTONUP:
                self.drag_idx = None

            elif event == cv2.EVENT_MOUSEMOVE and self.drag_idx is not None:
                h, w = state["img"].shape[:2]
                state["corners"][self.drag_idx, 0] = np.clip(ix, 0, w - 1)
                state["corners"][self.drag_idx, 1] = np.clip(iy, 0, h - 1)

        cv2.setMouseCallback(win, on_mouse)

        while True:
            img = self.current_image()
            rel_name = self.current_name()
            corners = self.get_corners(img, rel_name)

            state["img"] = img
            state["corners"] = corners
            state["name"] = rel_name

            while True:
                vis = self.draw(state["img"], state["corners"], state["name"])
                cv2.imshow(win, vis)
                k = cv2.waitKey(20) & 0xFF

                if k == ord("q"):
                    cv2.destroyAllWindows()
                    return

                elif k == ord("r"):
                    state["corners"] = propose_corners(state["img"])

                elif k == ord("f"):
                    # Toggle Fullscreen
                    is_fullscreen = not is_fullscreen
                    try:
                        cv2.setWindowProperty(
                            win,
                            cv2.WND_PROP_FULLSCREEN,
                            cv2.WINDOW_FULLSCREEN if is_fullscreen else cv2.WINDOW_NORMAL,
                        )
                    except Exception:
                        pass

                elif k == ord("z"):
                    # Toggle fit-to-window scaling
                    self.fit_to_window = not self.fit_to_window
                    print(f"[fit] fit_to_window={self.fit_to_window}")

                elif k == ord("a"):
                    self.save_one(state["name"], state["corners"])
                    self.i = min(len(self.paths) - 1, self.i + 1)
                    break

                elif k == ord("s"):
                    self.save_one(state["name"], state["corners"])

                elif k == ord("n"):
                    self.i = min(len(self.paths) - 1, self.i + 1)
                    break

                elif k == ord("p"):
                    self.i = max(0, self.i - 1)
                    break

                elif k == ord("c"):
                    # Center all corners to image center (for points outside the window)
                    h, w = state["img"].shape[:2]
                    center_x, center_y = w / 2, h / 2
                    corners = state["corners"]
                    
                    # Calculate the centroid of the current corners
                    current_center_x = np.mean(corners[:, 0])
                    current_center_y = np.mean(corners[:, 1])
                    
                    # Shift all corners so that the centroid is at the image center
                    offset_x = center_x - current_center_x
                    offset_y = center_y - current_center_y
                    
                    corners[:, 0] += offset_x
                    corners[:, 1] += offset_y
                    
                    # Ensure all points are within the image
                    corners[:, 0] = np.clip(corners[:, 0], 0, w - 1)
                    corners[:, 1] = np.clip(corners[:, 1], 0, h - 1)
                    
                    state["corners"] = corners
                    print(f"[center] Corners shifted to image center")

                elif k == ord("d"):
                    # Delete an unsuitable image and its associated label entry
                    current_path = self.current_path()
                    rel_name = state["name"]
                    
                    # Ask for confirmation (briefly tint the image red)
                    confirm_img = state["img"].copy()
                    confirm_img[:, :, 2] = np.clip(confirm_img[:, :, 2].astype(np.int32) + 100, 0, 255).astype(np.uint8)
                    cv2.putText(confirm_img, "DELETE? Press 'd' again to confirm, any other key to cancel",
                                (10, 90), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 255), 2, cv2.LINE_AA)
                    disp_w, disp_h, _, _ = compute_display_size(confirm_img.shape[1], confirm_img.shape[0], self.max_side)
                    if (disp_w, disp_h) != (confirm_img.shape[1], confirm_img.shape[0]):
                        confirm_img = cv2.resize(confirm_img, (disp_w, disp_h), interpolation=cv2.INTER_AREA)
                    cv2.imshow(win, confirm_img)
                    
                    confirm_key = cv2.waitKey(0) & 0xFF
                    if confirm_key == ord("d"):
                        # Delete label entry (if present)
                        if rel_name in self.labels:
                            del self.labels[rel_name]
                            self.save_all()
                            print(f"[delete] Label removed: {rel_name}")
                        
                        # Delete image file
                        try:
                            os.remove(current_path)
                            print(f"[delete] Image deleted: {current_path}")
                        except OSError as e:
                            print(f"[delete] Failed to delete: {e}")
                        
                        # Remove from path list
                        self.paths.remove(current_path)
                        
                        # Index anpassen
                        if len(self.paths) == 0:
                            print("[delete] No images left.")
                            cv2.destroyAllWindows()
                            return
                        self.i = min(self.i, len(self.paths) - 1)
                        break
                    else:
                        print("[delete] Canceled")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--img_dir", required=True, help="Directory containing images (may include subfolders).")
    ap.add_argument("--out", default="labels.jsonl", help="Output JSONL path.")
    ap.add_argument("--max_side", type=int, default=1400, help="Max display size (pixels) for the longest image side.")
    ap.add_argument("--recursive", action="store_true", help="Recursively include images from subfolders.")
    ap.add_argument("--start", type=str, default=None, help="Start at specific image (filename or partial match).")
    args = ap.parse_args()

    img_dir = os.path.abspath(args.img_dir)
    exts = (".jpg", ".jpeg", ".png", ".webp", ".bmp")

    paths: list[str] = []
    if args.recursive:
        for root, _, files in os.walk(img_dir):
            for fn in sorted(files):
                if fn.lower().endswith(exts):
                    paths.append(os.path.join(root, fn))
    else:
        for fn in sorted(os.listdir(img_dir)):
            if fn.lower().endswith(exts):
                paths.append(os.path.join(img_dir, fn))

    if not paths:
        raise SystemExit("No images found.")

    # Startindex finden (falls --start angegeben)
    start_idx = 0
    if args.start:
        for i, p in enumerate(paths):
            if args.start in os.path.basename(p):
                start_idx = i
                print(f"[start] Springe zu Bild {i+1}/{len(paths)}: {os.path.basename(p)}")
                break
        else:
            print(f"[start] Warnung: '{args.start}' nicht gefunden, starte bei erstem Bild.")

    labeler = Labeler(img_dir=img_dir, paths=paths, out_path=args.out, max_side=args.max_side)
    labeler.i = start_idx
    labeler.run()


if __name__ == "__main__":
    main()
