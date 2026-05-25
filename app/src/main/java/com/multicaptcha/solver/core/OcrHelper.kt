package com.multicaptcha.solver.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * OCR wrapper sử dụng Google ML Kit Text Recognition (on-device, free).
 * Dùng để trích text câu hỏi từ vùng ảnh captcha — accessibility không đọc được
 * vì FunCaptcha render content trong SurfaceView.
 *
 * Có upscale 3x trước khi feed vào ML Kit để tăng accuracy:
 *   Test với EasyOCR (Python) trên cùng ảnh:
 *     - Không upscale: "to" → "t0", "(1 of 5)" → "( of 5)" (miss số 1)
 *     - Upscale 3x  : đọc đúng tất cả
 *   ML Kit cũng improve tương tự khi text nhỏ.
 */
object OcrHelper {
    private const val TAG = "OcrHelper"
    private const val UPSCALE_FACTOR = 3

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Upscale bitmap với bilinear filter (Android không có LANCZOS built-in).
     * Bilinear khá ổn cho việc tăng độ chính xác OCR.
     */
    private fun upscale(src: Bitmap, factor: Int): Bitmap {
        if (factor <= 1) return src
        return Bitmap.createScaledBitmap(src, src.width * factor, src.height * factor, true)
    }

    /**
     * Đọc text trong bitmap. Trả về string đã được normalize (gộp nhiều dòng thành 1, trim).
     * @return text đọc được hoặc null nếu OCR fail / không có text.
     */
    suspend fun extractText(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
        try {
            // Upscale trước khi feed ML Kit để tăng accuracy với text nhỏ
            val scaledBitmap = upscale(bitmap, UPSCALE_FACTOR)
            DebugLogger.d(TAG, "OCR input: ${bitmap.width}x${bitmap.height} " +
                "→ upscaled ${scaledBitmap.width}x${scaledBitmap.height} (${UPSCALE_FACTOR}x)")

            val image = InputImage.fromBitmap(scaledBitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    // ML Kit's result.text đã sorted theo reading order
                    val normalized = result.text
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    DebugLogger.i(TAG, "OCR → \"$normalized\"")
                    // Recycle scaled bitmap nếu khác bitmap gốc
                    if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                    cont.resume(normalized.ifEmpty { null })
                }
                .addOnFailureListener { e ->
                    DebugLogger.e(TAG, "OCR process failed", e)
                    if (scaledBitmap !== bitmap) scaledBitmap.recycle()
                    cont.resume(null)
                }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "OCR setup exception", e)
            cont.resume(null)
        }
    }
}
