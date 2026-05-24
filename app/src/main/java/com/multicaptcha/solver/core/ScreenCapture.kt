package com.multicaptcha.solver.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Base64
import com.multicaptcha.solver.solver.SlotConfig
import java.io.ByteArrayOutputStream
import java.io.File

object ScreenCapture {

    private const val TAG      = "ScreenCapture"
    private const val TMP_ROOT = "/data/local/tmp/mcs_cap.png"

    // ── Chụp màn hình ────────────────────────────────────────────

    fun capture(): Bitmap? {
        return try {
            RootShell.exec("screencap -p $TMP_ROOT")

            val appTmp = File.createTempFile("mcs_cap", ".png")
            RootShell.exec("cp $TMP_ROOT ${appTmp.absolutePath} && chmod 644 ${appTmp.absolutePath}")

            val bmp = BitmapFactory.decodeFile(appTmp.absolutePath)
            appTmp.delete()

            DebugLogger.screenshotResult(bmp != null, bmp?.width ?: 0, bmp?.height ?: 0)
            bmp
        } catch (e: Exception) {
            DebugLogger.e(TAG, "capture() exception", e)
            null
        }
    }

    // ── Crop ─────────────────────────────────────────────────────

    fun cropSlot(full: Bitmap, slot: SlotConfig): Bitmap {
        val x = slot.xOffset.coerceIn(0, full.width - 1)
        val w = slot.width.coerceAtMost(full.width - x)
        val h = full.height
        val bmp = Bitmap.createBitmap(full, x, 0, w, h)
        DebugLogger.d(TAG, "cropSlot[${slot.index}] x=$x w=$w h=$h → ${bmp.width}x${bmp.height}")
        return bmp
    }

    fun cropRegion(src: Bitmap, rect: Rect): Bitmap {
        val x = rect.left.coerceIn(0, src.width - 1)
        val y = rect.top.coerceIn(0, src.height - 1)
        val w = rect.width().coerceAtMost(src.width - x)
        val h = rect.height().coerceAtMost(src.height - y)
        val bmp = Bitmap.createBitmap(src, x, y, w, h)
        DebugLogger.d(TAG, "cropRegion [${rect.left},${rect.top}]-[${rect.right},${rect.bottom}] → ${bmp.width}x${bmp.height}")
        return bmp
    }

    // ── Encode ───────────────────────────────────────────────────

    fun toBase64(bmp: Bitmap, quality: Int = 85): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes  = out.toByteArray()
        val b64    = Base64.encodeToString(bytes, Base64.NO_WRAP)
        DebugLogger.d(TAG, "toBase64 quality=$quality → ${bytes.size}B → b64=${b64.length}chars")
        return b64
    }

    // ── Green pixel detection ────────────────────────────────────

    /**
     * Tính tỉ lệ pixel màu xanh lá funcaptcha trong vùng [checkRect]
     * Funcaptcha green ≈ #00A651 và các biến thể
     */
    fun greenRatio(bmp: Bitmap, checkRect: Rect): Float {
        val x0 = checkRect.left.coerceIn(0, bmp.width - 1)
        val y0 = checkRect.top.coerceIn(0, bmp.height - 1)
        val x1 = checkRect.right.coerceIn(0, bmp.width)
        val y1 = checkRect.bottom.coerceIn(0, bmp.height)

        if (x1 <= x0 || y1 <= y0) return 0f

        var green = 0
        var total = 0

        // Sample mỗi 3 pixel cho nhanh
        for (x in x0 until x1 step 3) {
            for (y in y0 until y1 step 3) {
                total++
                if (isFuncaptchaGreen(bmp.getPixel(x, y))) green++
            }
        }

        return if (total == 0) 0f else green.toFloat() / total
    }

    /**
     * Kiểm tra màu xanh lá đặc trưng của funcaptcha button
     * R thấp (0-80), G cao (140-220), B trung bình (40-130)
     */
    private fun isFuncaptchaGreen(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8)  and 0xFF
        val b =  pixel         and 0xFF
        return r in 0..80 && g in 140..220 && b in 40..130
    }

    // ── Content detection ─────────────────────────────────────────

    /**
     * Kiểm tra bitmap có nội dung đa dạng không
     * (phân biệt màn hình có nội dung vs. nền trắng/trống)
     */
    fun hasSignificantContent(bmp: Bitmap): Boolean {
        if (bmp.width < 4 || bmp.height < 4) return false

        val stepX = (bmp.width  / 10).coerceAtLeast(1)
        val stepY = (bmp.height / 10).coerceAtLeast(1)
        val reds = mutableListOf<Int>()
        val greens = mutableListOf<Int>()
        val blues  = mutableListOf<Int>()

        for (x in 0 until bmp.width  step stepX)
        for (y in 0 until bmp.height step stepY) {
            val p = bmp.getPixel(x, y)
            reds.add((p shr 16) and 0xFF)
            greens.add((p shr 8) and 0xFF)
            blues.add(p and 0xFF)
        }

        val totalVariance = variance(reds) + variance(greens) + variance(blues)
        DebugLogger.d(TAG, "contentVariance=${totalVariance.toInt()}")
        return totalVariance > 500f
    }

    private fun variance(v: List<Int>): Float {
        if (v.isEmpty()) return 0f
        val mean = v.average()
        return v.map { (it - mean) * (it - mean) }.average().toFloat()
    }

    /**
     * Kiểm tra có popup/dialog che màn hình không.
     * Dialog thường có nền trắng tập trung ở giữa màn hình.
     * @return true nếu vùng trung tâm màn hình có nền trắng đồng nhất
     */
    fun isCenterDialogVisible(bitmap: Bitmap): Boolean {
        // Lấy vùng trung tâm 40%×30% của bitmap
        val l = (bitmap.width  * 0.30f).toInt()
        val t = (bitmap.height * 0.33f).toInt()
        val r = (bitmap.width  * 0.70f).toInt()
        val b = (bitmap.height * 0.60f).toInt()
        if (l >= r || t >= b) return false

        val region = try { cropRegion(bitmap, android.graphics.Rect(l, t, r, b)) }
                     catch (_: Exception) { return false }

        var brightPx = 0
        var total    = 0
        val step = 4
        for (y in 0 until region.height step step) {
            for (x in 0 until region.width step step) {
                val px = region.getPixel(x, y)
                val r  = android.graphics.Color.red(px)
                val g  = android.graphics.Color.green(px)
                val b  = android.graphics.Color.blue(px)
                if (r > 210 && g > 210 && b > 210) brightPx++
                total++
            }
        }
        val ratio = if (total > 0) brightPx.toFloat() / total else 0f
        android.util.Log.d("ScreenCapture", "isCenterDialogVisible: brightRatio=${"%.2f".format(ratio)}")
        return ratio > 0.80f   // >80% pixel trắng → có dialog
    }
}
