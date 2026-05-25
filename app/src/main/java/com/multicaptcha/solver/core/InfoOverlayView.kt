package com.multicaptcha.solver.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * Info overlay luôn hiển thị, đặt tại Y 10%-35% màn hình (NGOÀI vùng OCR/option/dots).
 * Hiện cho mỗi slot:
 *   - Text OCR đọc được (câu hỏi gửi API)
 *   - Số option đọc được từ dot count
 *
 * Không bị tắt bởi toggle overlay (vì nó nằm ngoài vùng làm việc nên không
 * gây nhiễu cho OCR/screencap).
 */
class InfoOverlayView(context: Context) : View(context) {

    private data class SlotInfo(var ocr: String = "—", var dots: Int = 0)
    private val slotInfos = mutableListOf<SlotInfo>()
    private var slotCount: Int = 0

    private val bgPaint = Paint().apply {
        color = Color.argb(170, 15, 18, 28)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 80, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 200, 200, 200)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 26f
        color = Color.argb(255, 100, 220, 255)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val ocrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        color = Color.WHITE
        typeface = Typeface.MONOSPACE
    }
    private val dotsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
        color = Color.argb(255, 255, 200, 80)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun setSlotCount(n: Int) {
        slotCount = n
        slotInfos.clear()
        repeat(n) { slotInfos.add(SlotInfo()) }
        postInvalidate()
    }

    fun updateSlot(index: Int, ocr: String?, dots: Int) {
        if (index !in 0 until slotInfos.size) return
        slotInfos[index] = SlotInfo(
            ocr  = ocr?.takeIf { it.isNotBlank() } ?: "—",
            dots = dots
        )
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (slotCount <= 0) return

        val W = width.toFloat()
        val H = height.toFloat()
        val topY = H * 0.10f
        val botY = H * 0.35f

        // Background bar
        canvas.drawRect(0f, topY, W, botY, bgPaint)
        canvas.drawRect(0f, topY, W, botY, borderPaint)

        val colW = W / slotCount
        for (i in 0 until slotCount) {
            val x = i * colW
            val padding = 12f
            val info = slotInfos.getOrNull(i) ?: continue

            // Header SLOT N
            canvas.drawText("SLOT $i", x + padding, topY + 30f, headerPaint)

            // Dots count (lớn, nổi bật)
            val dotsText = "Dots: ${info.dots}"
            canvas.drawText(dotsText, x + padding, topY + 60f, dotsPaint)

            // OCR text — wrap nhiều dòng
            drawWrapped(canvas, "OCR: ${info.ocr}",
                x + padding, topY + 90f,
                colW - 2 * padding, botY - 10f,
                ocrPaint)

            // Vertical separator
            if (i > 0) canvas.drawLine(x, topY, x, botY, sepPaint)
        }
    }

    private fun drawWrapped(
        canvas: Canvas, text: String,
        x: Float, startY: Float,
        maxWidth: Float, maxY: Float,
        paint: Paint
    ) {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (w in words) {
            val candidate = if (current.isEmpty()) w else "$current $w"
            if (paint.measureText(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines.add(current.toString())
                current = StringBuilder(w)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())

        val lineH = paint.textSize + 4f
        var y = startY
        for (line in lines) {
            if (y > maxY) {
                canvas.drawText("...", x, y - lineH + paint.textSize, paint)
                break
            }
            canvas.drawText(line, x, y, paint)
            y += lineH
        }
    }
}
