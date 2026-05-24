package com.multicaptcha.solver.solver

import android.graphics.Rect

/**
 * Thông tin 1 ô (floating window) trên màn hình
 */
data class SlotConfig(
    val index: Int,
    val xOffset: Int,   // pixel bắt đầu theo chiều ngang
    val width: Int,     // chiều rộng ô
    val height: Int,    // chiều cao ô (= chiều cao màn hình)
    val packageName: String = ""
) {
    // ────────────────────────────────────────────────────────────
    // Tọa độ tương đối các vùng quan trọng trong funcaptcha
    // Điều chỉnh nếu layout thực tế khác
    // ────────────────────────────────────────────────────────────

    /** Vùng nút "Start Puzzle / Commencer" — dùng để detect màu xanh */
    val startButtonRect: Rect get() = Rect(
        (width * 0.15f).toInt(),
        (height * 0.55f).toInt(),
        (width * 0.85f).toInt(),
        (height * 0.72f).toInt()
    )

    /** Tâm nút Start Puzzle (tọa độ tương đối trong slot) */
    val startButtonRelX: Float get() = 0.5f
    val startButtonRelY: Float get() = 0.63f

    // ────────────────────────────────────────────────────────────
    // Match-game layout (loại phổ biến nhất của funcaptcha)
    //
    //  ┌──────────────────────────────┐
    //  │  "Verifying you're not a bot"│  ~0-15%
    //  │  instruction text            │  ~15-28%
    //  ├─────────────┬────────────────┤
    //  │ Match This! │  [right image] │  ~28-65%
    //  │ (reference) │  (scrolling)   │
    //  ├─────────────┴────────────────┤
    //  │      [←]  ●○○○○○  [→]       │  ~65-78%
    //  │         [ Submit ]           │  ~78-90%
    //  └──────────────────────────────┘
    // ────────────────────────────────────────────────────────────

    /** Ảnh tham chiếu "Match This!" — phía trái, dùng để gửi API */
    val matchThisImageRect: Rect get() = Rect(
        (width * 0.02f).toInt(),
        (height * 0.28f).toInt(),
        (width * 0.50f).toInt(),
        (height * 0.65f).toInt()
    )

    /** Toàn bộ vùng 2 ảnh (gửi API khi cần context đầy đủ) */
    val challengeImageRect: Rect get() = Rect(
        (width * 0.02f).toInt(),
        (height * 0.28f).toInt(),
        (width * 0.98f).toInt(),
        (height * 0.65f).toInt()
    )

    /** Nút mũi tên phải → (dùng để scroll đến đáp án) */
    val rightArrowRelX: Float get() = 0.65f
    val rightArrowRelY: Float get() = 0.72f

    /** Nút mũi tên trái ← */
    val leftArrowRelX: Float get() = 0.35f
    val leftArrowRelY: Float get() = 0.72f

    /** Nút Submit (màu xanh lá, phía dưới mũi tên) */
    val submitRelX: Float get() = 0.50f
    val submitRelY: Float get() = 0.84f

    /** Vùng nút Submit để detect màu xanh (check puzzle đã sẵn sàng submit chưa) */
    val submitButtonRect: Rect get() = Rect(
        (width * 0.15f).toInt(),
        (height * 0.78f).toInt(),
        (width * 0.85f).toInt(),
        (height * 0.90f).toInt()
    )
}

object SlotManager {
    /**
     * Tạo danh sách slot chia đều màn hình theo chiều ngang
     * packages: list package name tương ứng với từng slot (theo thứ tự từ trái sang phải)
     */
    fun buildSlots(
        screenWidth: Int,
        screenHeight: Int,
        packages: List<String>
    ): List<SlotConfig> {
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
