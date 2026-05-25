package com.multicaptcha.solver.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Base64
import com.multicaptcha.solver.solver.SlotConfig
import java.io.ByteArrayOutputStream
import java.io.File

object ScreenCapture {

    private const val TAG = "ScreenCapture"

    // ── Chụp màn hình (thread-safe — unique temp file mỗi call) ──

    fun capture(): Bitmap? {
        return try {
            // Unique temp file → multiple coroutines parallel không race
            val nano = System.nanoTime()
            val rootTmp = "/data/local/tmp/mcs_cap_$nano.png"

            RootShell.exec("screencap -p $rootTmp")

            val appTmp = File.createTempFile("mcs_cap_", ".png")
            RootShell.exec("cp $rootTmp ${appTmp.absolutePath} && chmod 644 ${appTmp.absolutePath} && rm $rootTmp")

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

    /** JPEG encode (dùng cho ảnh đơn lẻ debug) */
    fun toBase64(bmp: Bitmap, quality: Int = 85): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        val b64   = Base64.encodeToString(bytes, Base64.NO_WRAP)
        DebugLogger.d(TAG, "toBase64 JPEG quality=$quality → ${bytes.size}B → b64=${b64.length}chars")
        return b64
    }

    /** PNG encode — dùng khi gửi ảnh ghép lên API (lossless, OMO dùng PNG) */
    fun toBase64Png(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        val b64   = Base64.encodeToString(bytes, Base64.NO_WRAP)
        DebugLogger.d(TAG, "toBase64 PNG → ${bytes.size}B → b64=${b64.length}chars")
        return b64
    }

    /**
     * So sánh 2 bitmap xem có giống nhau không (dùng để detect khi carousel quay lại position 1).
     * Sample lưới 8x8 pixel, tính trung bình sai số màu.
     * @return true nếu ảnh đủ giống (diff < threshold)
     */
    fun areSimilar(a: Bitmap, b: Bitmap, threshold: Double = 12.0): Boolean {
        if (a.width < 4 || a.height < 4 || b.width < 4 || b.height < 4) return false
        // Resize b về cùng kích thước a nếu khác size
        val bScaled = if (a.width != b.width || a.height != b.height)
            Bitmap.createScaledBitmap(b, a.width, a.height, false) else b
        val stepX = maxOf(1, a.width  / 8)
        val stepY = maxOf(1, a.height / 8)
        var diffSum = 0.0
        var count   = 0
        for (x in 0 until a.width  step stepX) {
            for (y in 0 until a.height step stepY) {
                val pa = a.getPixel(x, y)
                val pb = bScaled.getPixel(x, y)
                val dr = ((pa shr 16 and 0xFF) - (pb shr 16 and 0xFF)).toDouble()
                val dg = ((pa shr  8 and 0xFF) - (pb shr  8 and 0xFF)).toDouble()
                val db = ((pa        and 0xFF) - (pb        and 0xFF)).toDouble()
                diffSum += Math.sqrt(dr * dr + dg * dg + db * db)
                count++
            }
        }
        val avg = if (count > 0) diffSum / count else 999.0
        DebugLogger.d(TAG, "areSimilar: avgDiff=${"%.1f".format(avg)} thr=$threshold → ${avg < threshold}")
        return avg < threshold
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

    // ── Stitch utilities ─────────────────────────────────────────

    /**
     * Ghép danh sách bitmap thành 1 ảnh ngang (side-by-side).
     */
    fun stitchHorizontal(bitmaps: List<Bitmap>): Bitmap {
        if (bitmaps.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
        val totalWidth = bitmaps.sumOf { it.width }
        val maxHeight  = bitmaps.maxOf { it.height }
        val result = Bitmap.createBitmap(totalWidth, maxHeight, Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(result)
        var xOff = 0
        for (bmp in bitmaps) {
            canvas.drawBitmap(bmp, xOff.toFloat(), 0f, null)
            xOff += bmp.width
        }
        DebugLogger.d(TAG, "stitchHorizontal: ${bitmaps.size} imgs → ${result.width}x${result.height}")
        return result
    }

    /**
     * Xếp chồng: [top] bên trên, [bottom] bên dưới.
     * Chiều rộng lấy giá trị lớn hơn (phần thiếu để trống).
     */
    fun stackVertical(top: Bitmap, bottom: Bitmap): Bitmap {
        val width  = maxOf(top.width, bottom.width)
        val result = Bitmap.createBitmap(width, top.height + bottom.height, Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(result)
        canvas.drawBitmap(top, 0f, 0f, null)
        canvas.drawBitmap(bottom, 0f, top.height.toFloat(), null)
        DebugLogger.d(TAG, "stackVertical: top=${top.width}x${top.height} bot=${bottom.width}x${bottom.height} → ${result.width}x${result.height}")
        return result
    }

    // ── Dot counting (carousel page indicator) ──────────────────

    /**
     * Đếm số dots (chấm tròn) bằng Connected Component Labeling 2D (8-connectivity).
     *
     * Tại sao không dùng column-projection: filled dot và outline ring khi project
     * lên X có pattern khác nhau (ring có khoảng giữa rỗng), khó tune threshold cho
     * cả 2 case, đặc biệt khi dots adjacent gần nhau (khoảng giữa 2 dots ≈ khoảng
     * giữa ring → không phân biệt được).
     *
     * Algorithm:
     *   1. Threshold pixel < brightnessThreshold → binary mask "dark"
     *   2. BFS từng pixel chưa label, mở rộng 8-connectivity → mỗi blob = 1 component
     *   3. Filter component có pixels < minBlobPixels (noise)
     *   4. Đếm components còn lại
     *
     * Tested với ảnh 204x19 (1 filled + 15 outline rings) → đúng 16 dots.
     */
    fun countDots(bmp: Bitmap, brightnessThreshold: Int = 200, minBlobPixels: Int = 10): Int {
        val w = bmp.width
        val h = bmp.height
        if (w < 4 || h < 2) return 0

        // 1. Binary mask
        val dark = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = bmp.getPixel(x, y)
                val br = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
                dark[y * w + x] = br < brightnessThreshold
            }
        }

        // 2. BFS connected components 8-connectivity
        val visited = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()  // dùng index = y*w + x để tiết kiệm allocation
        var dotCount = 0

        for (startY in 0 until h) {
            for (startX in 0 until w) {
                val startIdx = startY * w + startX
                if (!dark[startIdx] || visited[startIdx]) continue

                // BFS từ pixel này
                var blobSize = 0
                stack.clear()
                stack.addLast(startIdx)
                while (stack.isNotEmpty()) {
                    val idx = stack.removeLast()
                    if (visited[idx]) continue
                    if (!dark[idx]) continue
                    visited[idx] = true
                    blobSize++

                    val y = idx / w
                    val x = idx - y * w
                    // 8 neighbours
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dy == 0 && dx == 0) continue
                            val ny = y + dy
                            val nx = x + dx
                            if (ny in 0 until h && nx in 0 until w) {
                                val nIdx = ny * w + nx
                                if (!visited[nIdx] && dark[nIdx]) {
                                    stack.addLast(nIdx)
                                }
                            }
                        }
                    }
                }

                if (blobSize >= minBlobPixels) dotCount++
            }
        }

        DebugLogger.d(TAG, "countDots ${w}x${h}: $dotCount dots (minBlobPixels=$minBlobPixels)")
        return dotCount
    }

    // ── Debug image save ────────────────────────────────────────

    /**
     * Lưu bitmap vào /storage/emulated/0/Download/solver_image/<filename>.png
     * Dùng để debug ảnh final trước khi gửi API.
     * Tự động giữ tối đa MAX_KEEP file (xoá cũ nhất khi vượt).
     */
    fun saveDebugImage(bmp: Bitmap, filename: String): String? {
        return try {
            val dir = java.io.File("/storage/emulated/0/Download/solver_image")
            if (!dir.exists()) dir.mkdirs()

            // Cleanup: giữ tối đa MAX_KEEP file, xoá những file cũ nhất
            val MAX_KEEP = 50
            val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            if (files.size >= MAX_KEEP) {
                val toDelete = files.take(files.size - MAX_KEEP + 1)
                toDelete.forEach { runCatching { it.delete() } }
                DebugLogger.d(TAG, "Cleanup ${toDelete.size} old solver_image file(s)")
            }

            val outFile = java.io.File(dir, filename)
            java.io.FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            DebugLogger.i(TAG, "Saved debug image: ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            DebugLogger.e(TAG, "saveDebugImage failed", e)
            null
        }
    }

}
