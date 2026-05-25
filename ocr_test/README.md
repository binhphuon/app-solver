# OCR Test

Tách OCR + parsing logic của Android app (`OcrHelper.kt` + `FuncaptchaSolver.kt`) ra Python để test crop ảnh captcha trực tiếp, không cần build APK lại mỗi lần thử.

## Cài đặt

```bash
cd ocr_test
pip install -r requirements.txt
```

Lần đầu chạy EasyOCR sẽ tự tải model tiếng Anh (~64MB).

## Sử dụng

```bash
# Test 1 ảnh
python ocr_test.py question_crop.png

# Test cả folder
python ocr_test.py crops/

# Nếu text trắng trên nền tối → invert trước
python ocr_test.py question_crop.png --invert

# Xem từng block detect được kèm confidence
python ocr_test.py question_crop.png -v
```

## Output mẫu

```
════ question_crop.png
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
