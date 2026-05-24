package com.multicaptcha.solver.solver

import android.graphics.Rect
import com.multicaptcha.solver.SolverConfig

/**
 * Thông tin 1 ô (floating window) trên màn hình.
 * Tọa độ tính từ SolverConfig (có thể điều chỉnh từ MainActivity).
 */
data class SlotConfig(
    val index: Int,
    val xOffset: Int,
    val width: Int,
    val height: Int,
    val packageName: String = ""
) {
    // ── Detect nút Start Puzzle ─────────────────────────────────
    val startButtonRect: Rect get() = Rect(
        (width  * 0.10f).toInt(),
        (height * SolverConfig.startDetectTop / 100f).toInt(),
        (width  * 0.90f).toInt(),
        (height * SolverConfig.startDetectBot / 100f).toInt()
    )
    val startButtonRelX: Float get() = 0.50f
    val startButtonRelY: Float get() = SolverConfig.startTapY / 100f

    // ── Match-game layout ───────────────────────────────────────
    //
    //  ┌──────────────────────────────────┐
    //  │ Verifying you're not a bot       │ 0-18%
    //  │ instruction text                 │ 18-38%
    //  ├──────────────┬───────────────────┤
    //  │ Match This!  │  [right image]    │ 38-68%
    //  │ (reference)  │  (scrolling)      │
    //  ├──────────────┴───────────────────┤
    //  │    [←] ●○○○○○ [→]               │ 68-78%
    //  │         [ Submit ]               │ 78-88%
    //  └──────────────────────────────────┘

    /** Ảnh tham chiếu "Match This!" — gửi lên API (X và Y đều chỉnh được) */
    val matchThisImageRect: Rect get() = Rect(
        (width  * SolverConfig.matchCropLeft  / 100f).toInt(),
        (height * SolverConfig.matchCropTop   / 100f).toInt(),
        (width  * SolverConfig.matchCropRight / 100f).toInt(),
        (height * SolverConfig.matchCropBot   / 100f).toInt()
    )

    /**
     * Ảnh option hiện tại (nửa phải carousel).
     * X bắt đầu ngay sau reference crop, cùng Y range.
     */
    val currentOptionRect: Rect get() = Rect(
        (width  * (SolverConfig.matchCropRight + 2f) / 100f).toInt(),
        (height * SolverConfig.matchCropTop / 100f).toInt(),
        (width  * 0.98f).toInt(),
        (height * SolverConfig.matchCropBot / 100f).toInt()
    )

    /** Toàn bộ vùng challenge (cả 2 ảnh) — để check hasContent */
    val challengeImageRect: Rect get() = Rect(
        (width  * 0.02f).toInt(),
        (height * (SolverConfig.matchCropTop - 8).coerceAtLeast(10f) / 100f).toInt(),
        (width  * 0.98f).toInt(),
        (height * (SolverConfig.matchCropBot + 8).coerceAtMost(90f) / 100f).toInt()
    )

    /** Mũi tên phải → */
    val rightArrowRelX: Float get() = SolverConfig.arrowTapX / 100f
    val rightArrowRelY: Float get() = SolverConfig.arrowTapY / 100f

    /** Mũi tên trái ← */
    val leftArrowRelX:  Float get() = (100 - SolverConfig.arrowTapX) / 100f
    val leftArrowRelY:  Float get() = SolverConfig.arrowTapY / 100f

    /** Nút Submit */
    val submitRelX: Float get() = 0.50f
    val submitRelY: Float get() = SolverConfig.submitTapY / 100f

    /** Vùng detect màu xanh của Submit */
    val submitButtonRect: Rect get() = Rect(
        (width  * 0.10f).toInt(),
        (height * SolverConfig.submitDetectTop / 100f).toInt(),
        (width  * 0.90f).toInt(),
        (height * SolverConfig.submitDetectBot / 100f).toInt()
    )
}

object SlotManager {
    fun buildSlots(screenWidth: Int, screenHeight: Int, packages: List<String>): List<SlotConfig> {
        val count = packages.size
        val slotWidth = screenWidth / count
        return packages.mapIndexed { i, pkg ->
            SlotConfig(
                index       = i,
                xOffset     = i * slotWidth,
                width       = slotWidth,
                height      = screenHeight,
                packageName = pkg
            )
        }
    }
}
