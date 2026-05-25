#!/usr/bin/env python3
"""
Test logic Android: OCR (OcrHelper.kt) + đếm dots (ScreenCapture.countDots).

Usage:
    # OCR câu hỏi (auto mode = ocr nếu --crop question)
    python ocr_test.py slot.png --crop question

    # Đếm dots (auto mode = dots nếu --crop dots)
    python ocr_test.py slot.png --crop dots

    # Force mode khác preset:
    python ocr_test.py slot.png --crop 40,70,95,76 --mode dots

    # Lưu ảnh đã crop để verify trực quan:
    python ocr_test.py slot.png --crop question -o cropped/

    # Batch + verbose
    python ocr_test.py screenshots/ --crop question -v

Crop presets (giống SolverConfig defaults):
    question : L=24    T=40    R=93    B=44.7   ← OCR text câu hỏi
    option   : L=48.5  T=45.6  R=80    B=65     ← 1 option carousel
    dots     : L=40    T=73.6  R=95    B=75.5   ← page indicator dots
    match    : L=25    T=45.6  R=48.5  B=65     ← ảnh "Match This!"

Modes:
    ocr   → EasyOCR đọc text + parse "(N of M)" + clean → API 'other'
    dots  → Project pixel tối lên trục X, đếm nhóm columns liên tiếp
    auto  → dots nếu --crop dots, ngược lại ocr (default)

Note:
    Android app dùng Google ML Kit text-recognition (on-device). Script này
    dùng EasyOCR vì pure-Python install bằng pip. Output có thể khác ML Kit
    chút (engine khác) nhưng đủ accuracy để verify crop có đọc được text không.
"""

import re
import sys
import argparse
from pathlib import Path


# ── Logic copy 1:1 từ FuncaptchaSolver.kt + OcrHelper.kt + ScreenCapture.kt ──

def normalize(text: str) -> str:
    """Gộp whitespace + trim — OcrHelper.kt:34"""
    return re.sub(r"\s+", " ", text).strip()


def count_dots(pil_img,
               brightness_threshold: int = 200,
               closing_iter: int = 1,
               min_blob_pixels: int = 4):
    """
    Đếm dots bằng Connected Component Labeling 2D (8-connectivity).

    Tại sao đổi từ column-projection sang CC:
      Filled dot và outline ring đều là 1 connected blob → đếm blob trực tiếp.
      Column projection bị mơ hồ giữa "khoảng giữa ring" (cần bridge) và
      "khoảng giữa 2 dots adjacent" (KHÔNG được bridge) — khi 2 khoảng này
      gần bằng nhau (dot strip nhỏ) → không phân biệt được.

    Args:
        brightness_threshold: pixel < threshold = "dark"
        closing_iter: morphological close (dilate→erode) lần này để fill
                      gap nhỏ trong ring outline. Default 1, 0 = disable.
        min_blob_pixels: bỏ blob nhỏ hơn ngần này pixels (chống noise).

    Return (count, components_list) — mỗi component có:
        x_range, y_range, pixels (số pixel tối thuộc blob)
    """
    import numpy as np
    from PIL import Image as PILImage, ImageFilter

    arr = np.array(pil_img.convert("L"))   # grayscale
    h, w = arr.shape
    if w < 4 or h < 2:
        return 0, []

    binary = arr < brightness_threshold

    # Morphological closing — fill khoảng hở nhỏ trong outline ring
    if closing_iter > 0:
        bin_img = PILImage.fromarray((binary.astype(np.uint8) * 255))
        for _ in range(closing_iter):
            bin_img = bin_img.filter(ImageFilter.MaxFilter(3))   # dilate
        for _ in range(closing_iter):
            bin_img = bin_img.filter(ImageFilter.MinFilter(3))   # erode
        binary = np.array(bin_img) > 128

    # Connected components: BFS 8-connectivity
    labels = np.zeros((h, w), dtype=np.int32)
    components = []
    next_label = 0

    for y in range(h):
        for x in range(w):
            if binary[y, x] and labels[y, x] == 0:
                next_label += 1
                ys_list, xs_list = [], []
                stack = [(y, x)]
                while stack:
                    cy, cx = stack.pop()
                    if not (0 <= cy < h and 0 <= cx < w):
                        continue
                    if not binary[cy, cx] or labels[cy, cx] != 0:
                        continue
                    labels[cy, cx] = next_label
                    ys_list.append(cy)
                    xs_list.append(cx)
                    for dy in (-1, 0, 1):
                        for dx in (-1, 0, 1):
                            if dy or dx:
                                stack.append((cy + dy, cx + dx))

                if len(xs_list) >= min_blob_pixels:
                    components.append({
                        "pixels":  len(xs_list),
                        "x_range": (min(xs_list), max(xs_list)),
                        "y_range": (min(ys_list), max(ys_list)),
                    })

    components.sort(key=lambda c: c["x_range"][0])
    return len(components), components


def parse_challenge_counter(text: str):
    """
    Tìm "(N of M)" → return (N, M) tuple hoặc None.
    FuncaptchaSolver.kt:128-130
    """
    m = re.search(r"\(\s*(\d+)\s*of\s*(\d+)\s*\)", text, re.IGNORECASE)
    if m:
        return int(m.group(1)), int(m.group(2))
    return None


def strip_counter(text: str) -> str:
    """
    Xoá "(N of M)" khỏi text + normalize. FuncaptchaSolver.kt:134-137
    """
    cleaned = re.sub(r"\(\s*\d+\s*of\s*\d+\s*\)", "", text, flags=re.IGNORECASE)
    return normalize(cleaned)


# ── Crop preset (đồng bộ với SolverConfig.kt) ─────────────────────────────

CROP_PRESETS = {
    "question": (24.0, 40.0, 93.0, 44.7),
    "option":   (48.5, 45.6, 80.0, 65.0),
    "dots":     (40.0, 73.6, 95.0, 75.5),
    "match":    (25.0, 45.6, 48.5, 65.0),
}


def parse_crop_arg(s: str):
    """
    Parse --crop arg.
      "question" / "option" / "dots" / "match" → preset
      "L,T,R,B"                                → custom (float %)
    Return (L, T, R, B) tuple of float percentages, hoặc None nếu không hợp lệ.
    """
    if s in CROP_PRESETS:
        return CROP_PRESETS[s]
    parts = [p.strip() for p in s.split(",")]
    if len(parts) != 4:
        return None
    try:
        return tuple(float(p) for p in parts)
    except ValueError:
        return None


def apply_crop(pil_img, crop_pct):
    """Cắt vùng theo % (L, T, R, B) — return PIL Image mới"""
    L, T, R, B = crop_pct
    w, h = pil_img.size
    box = (
        int(w * L / 100),
        int(h * T / 100),
        int(w * R / 100),
        int(h * B / 100),
    )
    return pil_img.crop(box), box


# ── Test runner ───────────────────────────────────────────────────────────

def run_ocr(reader, pil_img, invert: bool):
    """Chạy OCR, return (raw_normalized, blocks)."""
    from PIL import ImageOps
    import numpy as np
    img = ImageOps.invert(pil_img) if invert else pil_img
    blocks = reader.readtext(np.array(img))
    raw = " ".join(b[1] for b in blocks)
    return normalize(raw), blocks


def save_crop(pil_img, src_path: Path, out_dir: Path, crop_label: str):
    """Lưu ảnh cropped vào out_dir/ với filename = <stem>_<label>.png"""
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{src_path.stem}__{crop_label}.png"
    pil_img.save(out_path)
    return out_path


def test_image(reader, path: Path, mode: str, crop_pct, crop_label: str,
               invert: bool, verbose: bool, output_dir: Path = None,
               dot_params: dict = None):
    print()
    print(f"════ {path}")

    from PIL import Image
    try:
        img = Image.open(path).convert("RGB")
    except Exception as e:
        print(f"  ✗ Open image failed: {e}")
        return

    # Crop nếu yêu cầu
    crop_box = None
    if crop_pct is not None:
        img, crop_box = apply_crop(img, crop_pct)
        L, T, R, B = crop_pct
        print(f"  Crop          : L={L} T={T} R={R} B={B} (%)   → pixels {crop_box}")

        # Save cropped image nếu user yêu cầu
        if output_dir is not None:
            saved = save_crop(img, path, output_dir, crop_label)
            print(f"  Saved crop    : {saved}")

    if mode == "dots":
        # ── Dot count: Connected Components 2D ──
        dp = dot_params or {}
        count, comps = count_dots(
            img,
            brightness_threshold=dp.get("brightness", 200),
            closing_iter=dp.get("closing", 1),
            min_blob_pixels=dp.get("min_pixels", 4),
        )
        print(f"  Dot params    : bright={dp.get('brightness', 200)} "
              f"closing={dp.get('closing', 1)} "
              f"min_pixels={dp.get('min_pixels', 4)}")
        print(f"  Dot count     : {count}")
        if verbose and comps:
            print(f"  Components (sorted theo X):")
            for i, c in enumerate(comps, 1):
                xr = c["x_range"]
                yr = c["y_range"]
                print(f"    #{i:2d}: X={xr[0]}-{xr[1]}  Y={yr[0]}-{yr[1]}  "
                      f"({c['pixels']} px)")
    else:
        # ── OCR mode (default) ──
        try:
            raw, blocks = run_ocr(reader, img, invert)
        except Exception as e:
            print(f"  ✗ OCR failed: {e}")
            return

        print(f"  OCR raw       : \"{raw}\"")

        counter = parse_challenge_counter(raw)
        if counter:
            n, m = counter
            print(f"  Challenge cnt : {n} of {m}   (← info only, KHÔNG phải số options)")
        else:
            print(f"  Challenge cnt : <không tìm thấy '(N of M)'>")

        cleaned = strip_counter(raw)
        ok = len(cleaned) >= 10
        flag = "✓ OK" if ok else "✗ TOO SHORT — FuncaptchaSolver sẽ fallback về captchaOther"
        print(f"  → API 'other' : \"{cleaned}\"   [{flag}]")

        if verbose:
            print(f"  Blocks ({len(blocks)}):")
            for bbox, text, conf in blocks:
                print(f"    [{conf:.2f}] \"{text}\"")


def main():
    p = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("path", help="Ảnh hoặc folder để test")
    p.add_argument(
        "--crop", default=None,
        help="Crop trước khi xử lý. Preset: question|option|dots|match. "
             "Hoặc custom: 'L,T,R,B' theo % (vd: 24,40,93,44.7). "
             "Bỏ qua nếu ảnh đã crop sẵn.",
    )
    p.add_argument(
        "--mode", choices=["ocr", "dots", "auto"], default="auto",
        help="Phương thức xử lý. 'ocr' = đọc text. 'dots' = đếm dots. "
             "'auto' = chọn theo --crop preset (dots nếu --crop dots, "
             "ngược lại ocr). Default: auto.",
    )
    p.add_argument(
        "-o", "--output-dir", default=None,
        help="Folder lưu ảnh đã crop (chỉ khi --crop set). Lưu file .png "
             "tên '<original>__<label>.png' để check crop có đúng không.",
    )
    p.add_argument(
        "--invert", action="store_true",
        help="Invert màu trước khi OCR (cho text trắng trên nền tối)",
    )
    p.add_argument(
        "-v", "--verbose", action="store_true",
        help="Verbose: OCR blocks + confidence, hoặc dot column ranges",
    )
    # Dot tuning knobs (algorithm: connected components 2D)
    p.add_argument(
        "--dot-bright", type=int, default=200,
        help="Brightness threshold cho 'pixel tối' (0-255). Default 200. "
             "Tăng (220) nếu dots xám nhạt; giảm (180) nếu background hơi tối.",
    )
    p.add_argument(
        "--dot-closing", type=int, default=1,
        help="Số lần morphological close (dilate→erode) để fill gap nhỏ "
             "trong ring outline bị đứt. 0 = disable. Default 1.",
    )
    p.add_argument(
        "--dot-min-pixels", type=int, default=4,
        help="Bỏ blob nhỏ hơn ngần này pixels (chống noise). Default 4.",
    )
    args = p.parse_args()

    # Parse --crop
    crop_pct = None
    crop_label = "nocrop"
    if args.crop:
        crop_pct = parse_crop_arg(args.crop)
        if crop_pct is None:
            print(f"--crop không hợp lệ: '{args.crop}'")
            print(f"Dùng preset ({'|'.join(CROP_PRESETS)}) hoặc 'L,T,R,B'")
            sys.exit(1)
        crop_label = args.crop if args.crop in CROP_PRESETS else "custom"

    # Resolve --mode (auto = dots khi crop dots, ngược lại ocr)
    mode = args.mode
    if mode == "auto":
        mode = "dots" if args.crop == "dots" else "ocr"

    output_dir = Path(args.output_dir) if args.output_dir else None

    dot_params = {
        "brightness":  args.dot_bright,
        "closing":     args.dot_closing,
        "min_pixels":  args.dot_min_pixels,
    }

    # Chỉ load EasyOCR khi cần (mode=ocr); mode=dots không cần
    reader = None
    if mode == "ocr":
        try:
            import easyocr
        except ImportError:
            print("Chưa cài EasyOCR. Chạy:  pip install -r requirements.txt")
            sys.exit(1)
        print("Loading EasyOCR English model (lần đầu sẽ tải ~64MB)...")
        reader = easyocr.Reader(["en"], gpu=False, verbose=False)
    else:
        print(f"Mode: {mode} — skip OCR model load")

    target = Path(args.path)
    if target.is_file():
        test_image(reader, target, mode, crop_pct, crop_label,
                   args.invert, args.verbose, output_dir, dot_params)
    elif target.is_dir():
        exts = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}
        images = sorted(p for p in target.iterdir() if p.suffix.lower() in exts)
        if not images:
            print(f"Không có ảnh trong {target}")
            return
        print(f"Tìm thấy {len(images)} ảnh trong {target}")
        for img in images:
            test_image(reader, img, mode, crop_pct, crop_label,
                       args.invert, args.verbose, output_dir, dot_params)
    else:
        print(f"Không tồn tại: {target}")
        sys.exit(1)


if __name__ == "__main__":
    main()
