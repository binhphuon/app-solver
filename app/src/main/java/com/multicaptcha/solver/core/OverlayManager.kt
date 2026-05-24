package com.multicaptcha.solver.core

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.multicaptcha.solver.solver.SlotConfig
import com.multicaptcha.solver.solver.SlotState

/**
 * Floating overlay hiện trạng thái real-time lên màn hình
 * Yêu cầu SYSTEM_ALERT_WINDOW — tự grant qua root
 */
object OverlayManager {

    private const val TAG = "OverlayManager"

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout?        = null
    private var tvHeader: TextView?            = null
    private var tvLoop: TextView?              = null
    private val slotViews = mutableListOf<SlotStatusView>()

    private var debugView: DebugOverlayView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isShowing   = false

    // ── Setup ────────────────────────────────────────────────────

    /**
     * Gọi từ Service.onCreate()
     * Tự grant SYSTEM_ALERT_WINDOW qua root nếu chưa có
     */
    fun init(context: Context, slotCount: Int) {
        // Grant permission qua root (không cần user vào Settings)
        grantOverlayPermission(context)

        if (!Settings.canDrawOverlays(context)) {
            DebugLogger.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay disabled")
            return
        }

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        mainHandler.post {
            buildOverlayView(context, slotCount)
            showOverlay()
            showDebugOverlay(context)
        }
    }

    fun destroy() {
        mainHandler.post {
            try { rootView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            try { debugView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            rootView  = null
            debugView = null
            isShowing = false
        }
    }

    // ── Update methods (thread-safe) ─────────────────────────────

    fun updateHeader(text: String) {
        mainHandler.post { tvHeader?.text = text }
    }

    fun updateLoop(loopNum: Int, solvedTotal: Int, activeSlots: Int, totalSlots: Int) {
        mainHandler.post {
            tvLoop?.text = "Loop #$loopNum | ✓$solvedTotal solved | $activeSlots/$totalSlots active"
        }
    }

    fun updateSlot(index: Int, state: SlotState, detail: String = "") {
        if (index >= slotViews.size) return
        mainHandler.post {
            slotViews[index].update(state, detail)
        }
    }

    fun updateSlotStep(index: Int, step: String) {
        if (index >= slotViews.size) return
        mainHandler.post {
            slotViews[index].setStep(step)
        }
    }

    /**
     * Truyền danh sách slot để debug overlay vẽ đúng vị trí
     * Gọi sau khi buildSlots() trong SolverService
     */
    fun setSlots(slots: List<SlotConfig>) {
        mainHandler.post { debugView?.setSlots(slots) }
    }

    /**
     * Flash vòng tròn vàng tại vị trí tap tuyệt đối (pixel màn hình)
     * Gọi từ TouchInjector sau mỗi lần tap
     */
    fun showTapFlash(x: Int, y: Int) {
        mainHandler.post { debugView?.addTapMark(x, y) }
    }

    // ── Build overlay view ───────────────────────────────────────

    private fun buildOverlayView(context: Context, slotCount: Int) {
        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.argb(210, 15, 15, 15))
        }

        // Header
        tvHeader = textView(context, "● MultiCaptcha Solver", 13f, Color.WHITE, Typeface.BOLD)
        rootView!!.addView(tvHeader)

        // Loop counter
        tvLoop = textView(context, "Chờ bắt đầu...", 11f, Color.LTGRAY)
        rootView!!.addView(tvLoop)

        // Divider
        rootView!!.addView(divider(context))

        // Slot rows
        slotViews.clear()
        for (i in 0 until slotCount) {
            val sv = SlotStatusView(context, i)
            slotViews.add(sv)
            rootView!!.addView(sv.view)
        }
    }

    private fun showOverlay() {
        if (isShowing || rootView == null) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END   // góc trên phải
            x = dp(8)
            y = dp(40)
        }
        try {
            windowManager?.addView(rootView, params)
            isShowing = true
            DebugLogger.i(TAG, "Overlay shown")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to show overlay", e)
        }
    }

    private fun showDebugOverlay(context: Context) {
        if (windowManager == null) return
        val dv = DebugOverlayView(context)
        debugView = dv
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager?.addView(dv, params)
            DebugLogger.i(TAG, "Debug overlay shown")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to add debug overlay", e)
        }
    }

    // ── Permission via root ───────────────────────────────────────

    private fun grantOverlayPermission(context: Context) {
        if (Settings.canDrawOverlays(context)) return
        DebugLogger.i(TAG, "Granting SYSTEM_ALERT_WINDOW via root...")
        val pkg = context.packageName
        val result = RootShell.exec("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        DebugLogger.i(TAG, "appops result: '${result.ifEmpty { "ok (no output)" }}'")
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun textView(ctx: Context, text: String, sp: Float, color: Int,
                         style: Int = Typeface.NORMAL): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize  = sp
            setTextColor(color)
            typeface  = Typeface.create(Typeface.MONOSPACE, style)
        }

    private fun divider(ctx: Context): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.setMargins(0, dp(4), 0, dp(4)) }
            setBackgroundColor(Color.argb(120, 200, 200, 200))
        }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}

// ── Per-slot status view ──────────────────────────────────────────

class SlotStatusView(context: Context, private val index: Int) {

    private val tvState:  TextView
    private val tvDetail: TextView
    val view: LinearLayout

    init {
        view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
        }

        tvState = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            minWidth = dp(130)
        }

        tvDetail = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.argb(180, 200, 200, 200))
            typeface = Typeface.MONOSPACE
            maxWidth = dp(160)
            setSingleLine(true)
        }

        view.addView(tvState)
        view.addView(tvDetail)

        update(SlotState.IDLE)
    }

    fun update(state: SlotState, detail: String = "") {
        val (icon, color) = when (state) {
            SlotState.IDLE          -> "○ IDLE    " to Color.argb(180, 150, 150, 150)
            SlotState.START_VISIBLE -> "▶ START   " to Color.argb(255, 100, 200, 100)
            SlotState.PUZZLE_ACTIVE -> "⚡ PUZZLE  " to Color.argb(255, 255, 200, 50)
            SlotState.VERIFYING     -> "⏳ API...  " to Color.argb(255, 100, 180, 255)
            SlotState.SOLVED        -> "✓ SOLVED  " to Color.argb(255, 50, 220, 100)
        }
        tvState.text      = "[$index] $icon"
        tvState.setTextColor(color)
        if (detail.isNotEmpty()) tvDetail.text = detail
    }

    fun setStep(step: String) {
        tvDetail.text = step
    }

    private fun dp(v: Int): Int =
        (v * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
