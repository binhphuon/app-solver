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
 */
object OcrHelper {
    private const val TAG = "OcrHelper"

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Đọc text trong bitmap. Trả về string đã được normalize (gộp nhiều dòng thành 1, trim).
     * @return text đọc được hoặc null nếu OCR fail / không có text.
     */
    suspend fun extractText(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    // Gộp các block/line thành 1 chuỗi liền mạch, ngăn cách bằng space
                    val raw = result.text
                    val normalized = raw
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    DebugLogger.i(TAG, "OCR ${bitmap.width}x${bitmap.height} → \"$normalized\"")
                    cont.resume(normalized.ifEmpty { null })
                }
                .addOnFailureListener { e ->
                    DebugLogger.e(TAG, "OCR process failed", e)
                    cont.resume(null)
                }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "OCR setup exception", e)
            cont.resume(null)
        }
    }
}
