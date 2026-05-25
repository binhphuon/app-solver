# OCR / Dot count Test

Tách logic Android (`OcrHelper.kt` + `FuncaptchaSolver.kt` + `ScreenCapture.countDots`) ra Python để test crop ảnh captcha trực tiếp, không cần build APK lại mỗi lần thử.

**Hỗ trợ 2 mode:**
- **OCR** — đọc text câu hỏi (dùng EasyOCR), parse `(N of M)`, clean text gửi API
- **Dots** — đếm chấm tròn page indicator (port từ `ScreenCapture.countDots()`)

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
--dot-dilate N        # half-window 1D max filter (bridge khoảng giữa ring). Default 6.
                      # Tăng nếu outline ring lớn (vd 8-10)
                      # Giảm nếu các dots quá gần nhau (bị merge nhầm)
--dot-bright N        # threshold "pixel tối". Default 200. Tăng (220) nếu dots có
                      # màu xám nhạt; giảm (180) nếu dots đậm nhưng background hơi tối
--dot-cols-frac N     # column active nếu dark_count ≥ h / frac. Default 3.
                      # Tăng (4-5) nếu strip dots mỏng nên ring center khó pass
--dot-min-width N     # bỏ qua group hẹp hơn N cols. Default 2 (chống noise 1-pixel)
```

**Cách hoạt động** (port `ScreenCapture.countDots()` + cải tiến):

1. Convert sang grayscale, tính brightness mỗi pixel
2. Project lên trục X: mỗi cột đếm số pixel có brightness < `--dot-bright`
3. **1D max filter (dilate)** trên trục X với half-window = `--dot-dilate` →
   bridge khoảng giữa của outline ring (vốn rỗng) thành band liên tục
4. Cột "active" nếu giá trị ≥ h / `--dot-cols-frac`
5. Đếm nhóm liên tiếp các active cột (lọc nhóm < `--dot-min-width`) → số dots

Output mẫu:
```
════ slot.png
  Crop          : L=40 T=73.6 R=95 B=75.5 (%)   → pixels (220, 794, 522, 815)
  Dot count     : 5
  Dot groups (X column ranges):
    Dot 1: cols 12-18  (width=7px)
    Dot 2: cols 42-48  (width=7px)
    Dot 3: cols 72-78  (width=7px)
    Dot 4: cols 102-108 (width=7px)
    Dot 5: cols 132-138 (width=7px)
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

## Lưu ý về độ chính xác

Android app dùng **Google ML Kit text-recognition** (on-device, proprietary). Script này dùng **EasyOCR** vì pure-Python, dễ cài. Kết quả có thể khác chút:

| | ML Kit | EasyOCR |
|---|---|---|
| Tốc độ | Rất nhanh (native) | Vừa (CPU) |
| Accuracy in printed English | ~ngang nhau | ~ngang nhau |
| Setup | Tích hợp APK | `pip install` |

Nếu EasyOCR đọc được → ML Kit gần như chắc chắn cũng đọc được. Nếu EasyOCR không đọc được → có thể crop sai vùng, thử `--invert` hoặc xem `-v` blocks để debug.

## Workflow đề xuất

1. Trong app Android, vùng OCR sai (text không đọc được) → chụp screenshot, crop tay vùng câu hỏi → save thành PNG
2. Chạy `python ocr_test.py crop.png -v`
3. Xem blocks detect được — nếu confidence cao mà text vẫn sai → crop chưa đúng (lệch margin, crop quá nhỏ, lẫn icon...)
4. Adjust calibration `OCR question X/Y` trong app → screenshot lại → test
