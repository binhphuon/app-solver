package com.multicaptcha.solver.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import com.multicaptcha.solver.solver.SlotConfig

/**
 * Full-screen transparent overlay để debug:
 *  - Vẽ ranh giới từng slot (đường viền màu)
 *  - Vẽ các detection zone (startBtn, submitBtn, matchThis)
 *  - Vẽ crosshair tại vị trí click
 *  - Flash vàng khi có tap thực sự xảy ra (mờ dần sau 1.2s)
 */
class DebugOverlayView(context: Context) : View(context) {

    private var slots: List<SlotConfig> = emptyList()

    // Danh sách tap gần đây: Triple(absX, absY, timestampMs)
    private val tapMarks = mutableListOf<Triple<Int, Int, Long>>()
    private val tapLock  = Any()

    // ── Paints ───────────────────────────────────────────────────

    // Viền slot
    private val slotBorderPaint = buildPaint(Color.argb(220, 0, 200, 255), style = Paint.Style.STROKE, sw = 4f)

    // Zone startBtn (xanh lá)
    private val startFillPaint  = buildPaint(Color.argb(35,  50, 255, 50),  style = Paint.Style.FILL)
    private val startStrokePaint= buildPaint(Color.argb(220, 50, 255, 50),  style = Paint.Style.STROKE, sw = 2.5f)

    // Zone submitBtn (xanh dương)
    private val subFillPaint    = buildPaint(Color.argb(35,  50, 150, 255), style = Paint.Style.FILL)
    private val subStrokePaint  = buildPaint(Color.argb(220, 50, 150, 255), style = Paint.Style.STROKE, sw = 2.5f)

    // Zone matchThis (cam)
    private val matchFillPaint  = buildPaint(Color.argb(35,  255, 165, 0),  style = Paint.Style.FILL)
    private val matchStrokePaint= buildPaint(Color.argb(220, 255, 165, 0),  style = Paint.Style.STROKE, sw = 2.5f)

    // Zone option capture (tím/magenta)
    private val optFillPaint    = buildPaint(Color.argb(35,  220, 80, 220), style = Paint.Style.FILL)
    private val optStrokePaint  = buildPaint(Color.argb(220, 220, 80, 220), style = Paint.Style.STROKE, sw = 2.5f)

    // Zone question OCR (vàng nhạt)
    private val qFillPaint      = buildPaint(Color.argb(35,  255, 255, 100), style = Paint.Style.FILL)
    private val qStrokePaint    = buildPaint(Color.argb(220, 255, 255, 100), style = Paint.Style.STROKE, sw = 2.5f)

    // Zone dots count (cyan)
    private val dotsFillPaint   = buildPaint(Color.argb(35,  80, 230, 230),  style = Paint.Style.FILL)
    private val dotsStrokePaint = buildPaint(Color.argb(220, 80, 230, 230),  style = Paint.Style.STROKE, sw = 2.5f)

    // Label zone
    private val zoneLabelPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface  = Typeface.MONOSPACE
        textSize  = 26f
        style     = Paint.Style.FILL
    }
    private val labelBgPaint    = buildPaint(Color.argb(140, 0, 0, 0), style = Paint.Style.FILL)

    // Slot index label (lớn hơn)
    private val slotLabelPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize  = 40f
        color     = Color.argb(230, 0, 220, 255)
        style     = Paint.Style.FILL
    }

    // Crosshair click target
    private val crossHairFillPaint   = buildPaint(Color.TRANSPARENT, style = Paint.Style.FILL)
    private val crossHairStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // Tap flash
    private val tapFillPaint  = buildPaint(Color.argb(200, 255, 220, 0), style = Paint.Style.FILL)
    private val tapRingPaint  = buildPaint(Color.argb(255, 255, 80,  0),  style = Paint.Style.STROKE, sw = 3f)

    // Legend ở dưới trái
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 24f
        style    = Paint.Style.FILL
    }

    // ── Public API ───────────────────────────────────────────────

    fun setSlots(slots: List<SlotConfig>) {
        this.slots = slots
        postInvalidate()
    }

    /** Thêm dấu flash tại vị trí tap tuyệt đối (pixel màn hình) */
    fun addTapMark(x: Int, y: Int) {
        synchronized(tapLock) { tapMarks.add(Triple(x, y, System.currentTimeMillis())) }
        postInvalidate()
        postDelayed({
            synchronized(tapLock) {
                val cutoff = System.currentTimeMillis() - TAP_FADE_MS
                tapMarks.removeAll { it.third < cutoff }
            }
            postInvalidate()
        }, TAP_FADE_MS + 50)
    }

    // ── Draw ─────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawSlotZones(canvas)
        drawTapMarks(canvas)
        drawLegend(canvas)
    }

    private fun drawSlotZones(canvas: Canvas) {
        for (slot in slots) {
            val ox = slot.xOffset.toFloat()
            val oy = 0f
            val w  = slot.width.toFloat()
            val h  = slot.height.toFloat()

            // ── Viền slot ────
            canvas.drawRect(ox, oy, ox + w, oy + h, slotBorderPaint)

            // ── Label slot index ────
            val slotLabel = "SLOT ${slot.index}"
            val slotLabelW = slotLabelPaint.measureText(slotLabel)
            val lx = ox + w / 2 - slotLabelW / 2
            val ly = oy + 52f
            canvas.drawRect(lx - 6, ly - 42, lx + slotLabelW + 6, ly + 6, labelBgPaint)
            canvas.drawText(slotLabel, lx, ly, slotLabelPaint)

            // ── startButtonRect (xanh lá) ────
            drawZoneRect(canvas, ox, oy, slot.startButtonRect,
                startFillPaint, startStrokePaint,
                "startBtn detect", Color.argb(220, 50, 255, 50))

            // ── submitButtonRect (xanh dương) ────
            drawZoneRect(canvas, ox, oy, slot.submitButtonRect,
                subFillPaint, subStrokePaint,
                "submitBtn detect", Color.argb(220, 50, 150, 255))

            // ── matchThisImageRect (cam) ────
            drawZoneRect(canvas, ox, oy, slot.matchThisImageRect,
                matchFillPaint, matchStrokePaint,
                "matchThis crop", Color.argb(220, 255, 165, 0))

            // ── currentOptionRect (tím) ────
            drawZoneRect(canvas, ox, oy, slot.currentOptionRect,
                optFillPaint, optStrokePaint,
                "option capture", Color.argb(220, 220, 80, 220))

            // ── questionTextRect (vàng nhạt) ────
            drawZoneRect(canvas, ox, oy, slot.questionTextRect,
                qFillPaint, qStrokePaint,
                "OCR question", Color.argb(220, 255, 255, 100))

            // ── dotsCountRect (cyan) ────
            drawZoneRect(canvas, ox, oy, slot.dotsCountRect,
                dotsFillPaint, dotsStrokePaint,
                "dots count", Color.argb(220, 80, 230, 230))

            // ── Crosshair: vị trí sẽ click ────
            // Start Puzzle click
            drawCrossHair(canvas,
                ox + w * slot.startButtonRelX,
                oy + h * slot.startButtonRelY,
                Color.argb(220, 50, 255, 50), "tap Start")

            // Right arrow click
            drawCrossHair(canvas,
                ox + w * slot.rightArrowRelX,
                oy + h * slot.rightArrowRelY,
                Color.argb(220, 255, 200, 50), "tap →")

            // Submit click
            drawCrossHair(canvas,
                ox + w * slot.submitRelX,
                oy + h * slot.submitRelY,
                Color.argb(220, 100, 150, 255), "tap Submit")
        }
    }

    private fun drawZoneRect(
        canvas: Canvas,
        ox: Float, oy: Float,
        rect: Rect,
        fillPaint: Paint, strokePaint: Paint,
        label: String, labelColor: Int
    ) {
        val l = ox + rect.left
        val t = oy + rect.top
        val r = ox + rect.right
        val b = oy + rect.bottom
        canvas.drawRect(l, t, r, b, fillPaint)
        canvas.drawRect(l, t, r, b, strokePaint)
        // Label nhỏ ở góc trên trái của zone
        val lp = Paint(zoneLabelPaint).apply { color = labelColor }
        val tw = lp.measureText(label)
        canvas.drawRect(l + 2, t + 2, l + tw + 8, t + 30, labelBgPaint)
        canvas.drawText(label, l + 5, t + 24, lp)
    }

    private fun drawCrossHair(canvas: Canvas, cx: Float, cy: Float, color: Int, label: String) {
        val dotPaint = buildPaint(color, style = Paint.Style.FILL)
        canvas.drawCircle(cx, cy, 10f, dotPaint)

        val sp = buildPaint(color, style = Paint.Style.STROKE, sw = 2f)
        val arm = 25f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, sp)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, sp)

        // Label nhỏ bên phải crosshair
        val lp = Paint(zoneLabelPaint).apply { this.color = color; textSize = 22f }
        canvas.drawText(label, cx + arm + 4, cy + 8, lp)
    }

    private fun drawTapMarks(canvas: Canvas) {
        val now   = System.currentTimeMillis()
        val marks: List<Triple<Int, Int, Long>>
        synchronized(tapLock) { marks = tapMarks.toList() }

        for ((x, y, time) in marks) {
            val age   = now - time
            if (age > TAP_FADE_MS) continue
            val frac  = 1f - age.toFloat() / TAP_FADE_MS   // 1→0
            val alpha = (255 * frac).toInt().coerceIn(0, 255)
            val radius = 25f + (1f - frac) * 30f            // 25→55px

            tapFillPaint.alpha = alpha
            canvas.drawCircle(x.toFloat(), y.toFloat(), radius, tapFillPaint)

            tapRingPaint.alpha = alpha
            canvas.drawCircle(x.toFloat(), y.toFloat(), radius + 18f, tapRingPaint)

            // Label "TAP"
            val tp = Paint(zoneLabelPaint).apply {
                color     = Color.argb(alpha, 255, 220, 0)
                textSize  = 28f
                typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            canvas.drawText("TAP", x.toFloat() - 20, y.toFloat() - radius - 8, tp)
        }
    }

    private fun drawLegend(canvas: Canvas) {
        if (slots.isEmpty()) return
        val entries = listOf(
            "■ startBtn detect"  to Color.argb(220, 50, 255, 50),
            "■ submitBtn detect" to Color.argb(220, 50, 150, 255),
            "■ matchThis crop"   to Color.argb(220, 255, 165, 0),
            "■ option capture"   to Color.argb(220, 220, 80, 220),
            "■ OCR question"     to Color.argb(220, 255, 255, 100),
            "■ dots count"       to Color.argb(220, 80, 230, 230),
            "+ click target"     to Color.argb(180, 200, 200, 200),
            "● tap flash"        to Color.argb(220, 255, 220, 0)
        )
        var y = height - 20f
        for ((label, color) in entries.reversed()) {
            val lp = Paint(legendPaint).apply { this.color = color }
            val tw = lp.measureText(label)
            canvas.drawRect(6f, y - 22, tw + 14, y + 4, labelBgPaint)
            canvas.drawText(label, 10f, y, lp)
            y -= 30f
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun buildPaint(color: Int, style: Paint.Style, sw: Float = 1f): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color       = color
            this.style       = style
            this.strokeWidth = sw
        }

    companion object {
        const val TAP_FADE_MS = 1200L
    }
}
