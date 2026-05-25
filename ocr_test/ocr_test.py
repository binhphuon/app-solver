#!/usr/bin/env python3
"""
OCR test mô phỏng logic của FuncaptchaSolver / OcrHelper.kt.

Cho phép input ảnh đã crop (vùng câu hỏi captcha) và xem:
  - Raw text OCR đọc được (đã normalize whitespace, giống OcrHelper.kt)
  - Parse "(N of M)" → challenge counter (info only, không phải số options)
  - Cleaned text gửi lên OMOcaptcha API (bỏ "(N of M)")
  - Validation: text < 10 ký tự thì FuncaptchaSolver sẽ fallback về captchaOther

Usage:
    # Nếu ảnh đã crop sẵn vùng câu hỏi
    python ocr_test.py path/to/cropped_question.png

    # Nếu feed nguyên slot screenshot — script tự crop dùng % của Android app
    python ocr_test.py slot_full.png --crop question
    python ocr_test.py slot_full.png --crop 24,40,93,44.7   # custom L,T,R,B %

    # Batch
    python ocr_test.py path/to/folder/

    # Tuỳ chọn
    python ocr_test.py img.png --invert      # invert (text trắng/nền tối)
    python ocr_test.py img.png -v            # show từng block + confidence

Crop presets (giống SolverConfig defaults):
    question : L=24    T=40    R=93    B=44.7   ← Y câu hỏi
    option   : L=48.5  T=45.6  R=80    B=65     ← 1 option carousel
    dots     : L=40    T=73.6  R=95    B=75.5   ← page indicator dots
    match    : L=25    T=45.6  R=48.5  B=65     ← ảnh "Match This!"

Note:
    Android app dùng Google ML Kit text-recognition (on-device). Script này
    dùng EasyOCR vì pure-Python install bằng pip. Output có thể khác ML Kit
    chút (engine khác) nhưng đủ accuracy để verify crop có đọc được text không.
"""

import re
import sys
import argparse
from pathlib import Path


# ── Logic copy 1:1 từ FuncaptchaSolver.kt + OcrHelper.kt ──────────────────

def normalize(text: str) -> str:
    """Gộp whitespace + trim — OcrHelper.kt:34"""
    return re.sub(r"\s+", " ", text).strip()


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

def ocr_image(reader, path: Path, invert: bool, crop_pct=None):
    """
    Chạy OCR. Nếu crop_pct (L,T,R,B %) → cắt trước khi OCR.
    Return (raw_normalized, blocks_list[(bbox, text, conf)], crop_box_px hoặc None)
    """
    from PIL import Image, ImageOps
    import numpy as np

    img = Image.open(path).convert("RGB")
    crop_box_px = None
    if crop_pct is not None:
        img, crop_box_px = apply_crop(img, crop_pct)
    if invert:
        img = ImageOps.invert(img)
    result = reader.readtext(np.array(img))

    raw = " ".join(item[1] for item in result)
    return normalize(raw), result, crop_box_px


def test_image(reader, path: Path, invert: bool, verbose: bool, crop_pct=None):
    print()
    print(f"════ {path}")

    try:
        raw, blocks, crop_box = ocr_image(reader, path, invert, crop_pct)
    except Exception as e:
        print(f"  ✗ OCR failed: {e}")
        return

    if crop_pct is not None:
        L, T, R, B = crop_pct
        print(f"  Crop          : L={L} T={T} R={R} B={B} (%)   → pixels {crop_box}")
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
        help="Crop trước khi OCR. Preset: question|option|dots|match. "
             "Hoặc custom: 'L,T,R,B' theo % (vd: 24,40,93,44.7). "
             "Bỏ qua nếu ảnh đã crop sẵn.",
    )
    p.add_argument(
        "--invert", action="store_true",
        help="Invert màu trước khi OCR (cho text trắng trên nền tối)",
    )
    p.add_argument(
        "-v", "--verbose", action="store_true",
        help="Hiển thị từng block detect được kèm confidence",
    )
    args = p.parse_args()

    crop_pct = None
    if args.crop:
        crop_pct = parse_crop_arg(args.crop)
        if crop_pct is None:
            print(f"--crop không hợp lệ: '{args.crop}'")
            print(f"Dùng preset ({'|'.join(CROP_PRESETS)}) hoặc 'L,T,R,B'")
            sys.exit(1)

    try:
        import easyocr
    except ImportError:
        print("Chưa cài EasyOCR. Chạy:  pip install -r requirements.txt")
        sys.exit(1)

    print("Loading EasyOCR English model (lần đầu sẽ tải ~64MB)...")
    reader = easyocr.Reader(["en"], gpu=False, verbose=False)

    target = Path(args.path)
    if target.is_file():
        test_image(reader, target, args.invert, args.verbose, crop_pct)
    elif target.is_dir():
        exts = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}
        images = sorted(p for p in target.iterdir() if p.suffix.lower() in exts)
        if not images:
            print(f"Không có ảnh trong {target}")
            return
        print(f"Tìm thấy {len(images)} ảnh trong {target}")
        for img in images:
            test_image(reader, img, args.invert, args.verbose, crop_pct)
    else:
        print(f"Không tồn tại: {target}")
        sys.exit(1)


if __name__ == "__main__":
    main()
