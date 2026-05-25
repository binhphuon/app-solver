package com.multicaptcha.solver.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.multicaptcha.solver.solver.SlotState

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

    private data class SlotInfo(
        var ocr:   String = "—",
        var dots:  Int    = 0,
        var state: SlotState = SlotState.IDLE,
        var step:  String = "",
    )
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
    private val statePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val stepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f
        color = Color.argb(220, 180, 220, 255)
        typeface = Typeface.MONOSPACE
    }

    private fun stateColor(s: SlotState): Int = when (s) {
        SlotState.IDLE          -> Color.argb(220, 150, 150, 150)
        SlotState.START_VISIBLE -> Color.argb(255, 100, 255, 100)
        SlotState.PUZZLE_ACTIVE -> Color.argb(255, 255, 200, 50)
        SlotState.TRY_AGAIN     -> Color.argb(255, 255, 80, 80)
        SlotState.VERIFYING     -> Color.argb(255, 100, 180, 255)
        SlotState.SOLVED        -> Color.argb(255, 50, 220, 100)
    }

    private fun stateIcon(s: SlotState): String = when (s) {
        SlotState.IDLE          -> "○ IDLE"
        SlotState.START_VISIBLE -> "▶ START"
        SlotState.PUZZLE_ACTIVE -> "⚡ PUZZLE"
        SlotState.TRY_AGAIN     -> "✗ RETRY"
        SlotState.VERIFYING     -> "⏳ API"
        SlotState.SOLVED        -> "✓ SOLVED"
    }

    fun setSlotCount(n: Int) {
        slotCount = n
        slotInfos.clear()
        repeat(n) { slotInfos.add(SlotInfo()) }
        postInvalidate()
    }

    /** Update OCR text + dot count (gọi từ FuncaptchaSolver sau OCR) */
    fun updateSlot(index: Int, ocr: String?, dots: Int) {
        if (index !in 0 until slotInfos.size) return
        val cur = slotInfos[index]
        cur.ocr  = ocr?.takeIf { it.isNotBlank() } ?: "—"
        cur.dots = dots
        postInvalidate()
    }

    /** Update slot state (icon + màu) */
    fun updateSlotState(index: Int, state: SlotState) {
        if (index !in 0 until slotInfos.size) return
        slotInfos[index].state = state
        postInvalidate()
    }

    /** Update step text (mô tả ngắn step hiện tại) */
    fun updateSlotStep(index: Int, step: String) {
        if (index !in 0 until slotInfos.size) return
        slotInfos[index].step = step
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

            // Header SLOT N + STATE icon (cùng dòng)
            canvas.drawText("SLOT $i", x + padding, topY + 28f, headerPaint)
            statePaint.color = stateColor(info.state)
            val stateText = stateIcon(info.state)
            val headerW = headerPaint.measureText("SLOT $i")
            canvas.drawText(stateText, x + padding + headerW + 16f, topY + 28f, statePaint)

            // Dots count
            canvas.drawText("Dots: ${info.dots}", x + padding, topY + 56f, dotsPaint)

            // OCR text — wrap, leave room for step text bên dưới
            val ocrEndY = drawWrapped(canvas, "OCR: ${info.ocr}",
                x + padding, topY + 82f,
                colW - 2 * padding, botY - 28f,
                ocrPaint)

            // Step text (subtle, dưới cùng) nếu có
            if (info.step.isNotEmpty()) {
                canvas.drawText("→ ${info.step}".take(40),
                    x + padding, botY - 8f, stepPaint)
            }

            // Vertical separator
            if (i > 0) canvas.drawLine(x, topY, x, botY, sepPaint)
        }
    }

    private fun drawWrapped(
        canvas: Canvas, text: String,
        x: Float, startY: Float,
        maxWidth: Float, maxY: Float,
        paint: Paint
    ): Float {
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
        return y
    }
}
