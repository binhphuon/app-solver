# OCR / Dot count Test

Tách logic Android (`OcrHelper.kt` + `FuncaptchaSolver.kt` + `ScreenCapture.countDots`) ra Python để test crop ảnh captcha trực tiếp, không cần build APK lại mỗi lần thử.

**Hỗ trợ 2 mode:**
- **OCR** — đọc text câu hỏi, parse `(N of M)`, clean text gửi API
- **Dots** — đếm chấm tròn page indicator (Connected Components 2D)

**2 OCR engines:**
- `rapid` (default) — RapidOCR (ONNX, dùng models PaddleOCR). Tốt nhất cho printed text nhỏ.
- `easy` — EasyOCR fallback (cài thêm bằng `pip install easyocr`).

## Cài đặt

```bash
cd ocr_test
pip install -r requirements.txt
```

Lần đầu chạy EasyOCR sẽ tự tải model tiếng Anh (~64MB).

## Sử dụng

### Cách 1: Crop tay vùng câu hỏi rồi feed

```bash
python ocr_test.py cropped_question.png
```

### Cách 2 (khuyên dùng): Feed nguyên slot, để script tự crop

Cách này test luôn cả crop calibration. Script crop dùng % giống hệt Android app.

```bash
# Dùng preset
python ocr_test.py slot_full.png --crop question

# Hoặc custom % (L,T,R,B) — giống etQLeft/Top/Right/Bot trong app
python ocr_test.py slot_full.png --crop 24,40,93,44.7
```

**Presets** (đồng bộ với `SolverConfig.kt` defaults):

| Preset      | L     | T     | R    | B     | Mục đích                       |
|-------------|-------|-------|------|-------|--------------------------------|
| `question`  | 24    | 40    | 93   | 44.7  | Vùng OCR text câu hỏi          |
| `option`    | 48.5  | 45.6  | 80   | 65    | 1 ảnh option carousel          |
| `dots`      | 40    | 73.6  | 95   | 75.5  | Page indicator dots            |
| `match`     | 25    | 45.6  | 48.5 | 65    | Ảnh "Match This!" reference    |

### Đếm dots (page indicator)

```bash
# Auto detect: --crop dots → mode dots
python ocr_test.py slot.png --crop dots

# Verbose: hiện X column ranges của từng dot detect được
python ocr_test.py slot.png --crop dots -v

# Ảnh đã crop sẵn vùng dots
python ocr_test.py dots_only.png --mode dots -v
```

**Tuning knobs** (nếu đếm sai):

```bash
--dot-bright N        # threshold "pixel tối" (0-255). Default 200.
                      # Tăng (220) nếu dots xám nhạt; giảm (180) nếu background hơi tối
--dot-closing N       # số lần morphological close (dilate→erode) để fill gap
                      # nhỏ trong ring outline bị đứt. Default 1, 0 = disable
--dot-min-pixels N    # bỏ blob nhỏ hơn N pixels (chống noise). Default 4
```

**Cách hoạt động** (Connected Component Labeling 2D, 8-connectivity):

1. Convert sang grayscale, threshold thành binary (`pixel < bright = dark`)
2. **Morphological closing** (dilate→erode) `closing_iter` lần — fill các gap
   nhỏ trong outline ring nếu stroke bị đứt
3. **BFS** từng pixel chưa label, mở rộng theo 8-connectivity → mỗi blob
   là 1 connected component
4. Bỏ blob < `--dot-min-pixels` (noise)
5. Số components còn lại = số dots

**Tại sao đổi từ column-projection sang CC?**

Column-projection (version cũ) project tất cả pixel tối lên trục X rồi đếm
nhóm columns. Gặp outline ring (vòng rỗng), khoảng giữa ring không có pixel
tối → cần dilate để bridge. Nhưng khi 2 dots adjacent gần nhau, khoảng giữa
2 dots ≈ khoảng giữa ring → không phân biệt được, gây under/over-count.

CC labeling 2D giữ thông tin Y cũng nên distinguish chính xác:
- Filled dot = 1 blob (solid circle)
- Outline ring = 1 blob (ring là 1 closed loop)
- 2 dots adjacent = 2 blobs riêng (rỗng giữa rõ ràng)

Output mẫu (verbose):
```
════ dot.png
  Dot params    : bright=200 closing=1 min_pixels=4
  Dot count     : 14
  Components (sorted theo X):
    # 1: X=10-17  Y=2-9   (52 px)        ← filled dot
    # 2: X=24-33  Y=2-9   (28 px)        ← outline ring
    # 3: X=38-47  Y=2-9   (28 px)
    ...
```

### Lưu ảnh đã crop ra folder

Hữu ích để verify crop có đúng vùng không:

```bash
python ocr_test.py slot.png --crop question -o cropped/
# → tạo cropped/slot__question.png
```

### Other options

```bash
# Batch test cả folder
python ocr_test.py crops/ --crop question

# Text trắng trên nền tối → invert (chỉ ảnh hưởng OCR)
python ocr_test.py img.png --crop question --invert

# Force mode khác với preset
python ocr_test.py slot.png --crop 40,70,95,76 --mode dots

# Show OCR blocks + confidence (debug crop sai)
python ocr_test.py slot.png --crop question -v
```

## Output mẫu

```
════ slot_full.png
  Crop          : L=24 T=40 R=93 B=44.7 (%)   → pixels (134, 432, 519, 482)
  OCR raw       : "Using the arrows, move the person to the indicated seat. (1 of 5)"
  Challenge cnt : 1 of 5   (← info only, KHÔNG phải số options)
  → API 'other' : "Using the arrows, move the person to the indicated seat."   [✓ OK]
```

## Logic copy từ Android

| Hàm Python                  | Tương đương Kotlin                                |
|-----------------------------|---------------------------------------------------|
| `normalize()`               | `OcrHelper.kt` line 33-35                         |
| `parse_challenge_counter()` | `FuncaptchaSolver.kt` line 128-130                |
| `strip_counter()`           | `FuncaptchaSolver.kt` line 134-137                |
| `len(cleaned) >= 10` check  | `FuncaptchaSolver.kt` line 141 (`takeIf { it.length >= 10 }`) |
| `count_dots()`              | `ScreenCapture.kt` `countDots()`                  |

## So sánh accuracy giữa engines

Test trên `text.png` (343×48) — câu hỏi captcha thực tế:

| Engine        | OCR raw                                                                | API text gửi đi |
|---------------|------------------------------------------------------------------------|----------------|
| **rapid 1x**  | "Using the arrows**,** move the person **to** the indicated seat of 5)"| ✓ "Using the arrows, move the person to the indicated seat" |
| easy 1x       | "Using the arrows**;** move the person **t0** the indicated seat ( of 5)" | ✗ punctuation + chữ sai + miss số 1 |
| easy 3x       | "the arrows; move the person to the indicated seat (1 of 5) Using"     | ✗ block order shuffle → "Using" về cuối |
| paddleocr     | crash trên Windows (compat issue PaddlePaddle)                         | — |

**Kết luận**: RapidOCR (default) cho kết quả sạch nhất. Dùng models của PaddleOCR
nhưng compile qua ONNX nên không cần PaddlePaddle backend.

## Tăng accuracy thêm nếu cần

```bash
# Upscale ảnh trước OCR (LANCZOS) — chỉ giúp cho EasyOCR text nhỏ
python ocr_test.py text.png --engine easy --ocr-scale 3

# Invert màu — cho text trắng trên nền tối
python ocr_test.py text.png --invert

# Verbose: xem từng block + confidence
python ocr_test.py text.png -v
```

## Lưu ý về regex strip counter

Script tự strip pattern `(N of M)` ở cuối text trước khi gửi API, lenient với
các variants OCR đọc thiếu ký tự:

| OCR output                  | Sau strip                |
|-----------------------------|--------------------------|
| `...seat (1 of 5)`          | `...seat`                |
| `...seat ( of 5)` (miss N)  | `...seat`                |
| `...seat of 5)` (miss `(`)  | `...seat`                |
| `...seat (1 of 5` (miss `)`)| `...seat`                |

Regex: `\s*\(?\s*\d*\s*of\s*\d+\s*\)?\s*$` (anchor `$` cuối string).

## Engine cho Android

Android `OcrHelper.kt` vẫn dùng **Google ML Kit** (built-in, free, on-device).
Đã thêm 3x upscale trước khi gọi ML Kit để tăng accuracy nếu text nhỏ.

Switch Android sang RapidOCR sẽ cần ONNX Runtime Mobile + ~50MB model → tăng APK
size đáng kể. ML Kit cho FunCaptcha thường đủ tốt.

## Workflow đề xuất

1. Trong app Android, vùng OCR sai (text không đọc được) → chụp screenshot, crop tay vùng câu hỏi → save thành PNG
2. Chạy `python ocr_test.py crop.png -v`
3. Xem blocks detect được — nếu confidence cao mà text vẫn sai → crop chưa đúng (lệch margin, crop quá nhỏ, lẫn icon...)
4. Adjust calibration `OCR question X/Y` trong app → screenshot lại → test
