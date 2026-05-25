#!/usr/bin/env python3
"""
OCR test mô phỏng logic của FuncaptchaSolver / OcrHelper.kt.

Cho phép input ảnh đã crop (vùng câu hỏi captcha) và xem:
  - Raw text OCR đọc được (đã normalize whitespace, giống OcrHelper.kt)
  - Parse "(N of M)" → challenge counter (info only, không phải số options)
  - Cleaned text gửi lên OMOcaptcha API (bỏ "(N of M)")
  - Validation: text < 10 ký tự thì FuncaptchaSolver sẽ fallback về captchaOther

Usage:
    python ocr_test.py path/to/cropped_question.png
    python ocr_test.py path/to/folder/                 # batch tất cả ảnh
    python ocr_test.py img.png --invert                # invert trước khi OCR
    python ocr_test.py img.png -v                      # show từng block

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


# ── Test runner ───────────────────────────────────────────────────────────

def ocr_image(reader, path: Path, invert: bool):
    """Chạy OCR, return (raw_normalized, blocks_list[(bbox, text, conf)])"""
    if invert:
        from PIL import Image, ImageOps
        import numpy as np
        img = Image.open(path).convert("RGB")
        img = ImageOps.invert(img)
        result = reader.readtext(np.array(img))
    else:
        result = reader.readtext(str(path))

    # ML Kit trả về cả block — ta gộp theo thứ tự đọc giống Kotlin (result.text)
    raw = " ".join(item[1] for item in result)
    return normalize(raw), result


def test_image(reader, path: Path, invert: bool, verbose: bool):
    print()
    print(f"════ {path}")

    try:
        raw, blocks = ocr_image(reader, path, invert)
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
        "--invert", action="store_true",
        help="Invert màu trước khi OCR (cho text trắng trên nền tối)",
    )
    p.add_argument(
        "-v", "--verbose", action="store_true",
        help="Hiển thị từng block detect được kèm confidence",
    )
    args = p.parse_args()

    try:
        import easyocr
    except ImportError:
        print("Chưa cài EasyOCR. Chạy:  pip install -r requirements.txt")
        sys.exit(1)

    print("Loading EasyOCR English model (lần đầu sẽ tải ~64MB)...")
    reader = easyocr.Reader(["en"], gpu=False, verbose=False)

    target = Path(args.path)
    if target.is_file():
        test_image(reader, target, args.invert, args.verbose)
    elif target.is_dir():
        exts = {".png", ".jpg", ".jpeg", ".bmp", ".webp"}
        images = sorted(p for p in target.iterdir() if p.suffix.lower() in exts)
        if not images:
            print(f"Không có ảnh trong {target}")
            return
        print(f"Tìm thấy {len(images)} ảnh trong {target}")
        for img in images:
            test_image(reader, img, args.invert, args.verbose)
    else:
        print(f"Không tồn tại: {target}")
        sys.exit(1)


if __name__ == "__main__":
    main()
