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

    /**
     * Vùng quét màu xanh để detect nút "Start Puzzle"
     * Đo từ debug overlay: button hiện ở khoảng y 65-74% của slot (720px)
     * Mở rộng thêm biên trên/dưới để bắt chắc hơn
     */
    val startButtonRect: Rect get() = Rect(
        (width * 0.10f).toInt(),
        (height * 0.52f).toInt(),   // từ 55% → 52% (mở rộng lên trên)
        (width * 0.90f).toInt(),
        (height * 0.82f).toInt()    // từ 72% → 82% (mở rộng xuống dưới)
    )

    /**
     * Tâm nút Start Puzzle — đo từ debug overlay
     * button center ≈ y 70% của slot (504px / 720px)
     */
    val startButtonRelX: Float get() = 0.5f
    val startButtonRelY: Float get() = 0.70f   // từ 0.63 → 0.70

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

    /**
     * Ảnh tham chiếu "Match This!" — nửa trái vùng 2 ảnh
     * Đo từ debug overlay slot 0 (puzzle active):
     *   images bắt đầu ở y≈31%, kết thúc y≈65%
     *   nửa trái: x 2-50%
     */
    val matchThisImageRect: Rect get() = Rect(
        (width * 0.02f).toInt(),
        (height * 0.30f).toInt(),   // từ 28% → 30%
        (width * 0.50f).toInt(),
        (height * 0.67f).toInt()    // từ 65% → 67%
    )

    /**
     * Toàn bộ vùng challenge (cả 2 ảnh) — dùng để check hasContent
     * Mở rộng để bắt được puzzle dù layout có chênh lệch nhỏ
     */
    val challengeImageRect: Rect get() = Rect(
        (width * 0.02f).toInt(),
        (height * 0.20f).toInt(),   // từ 28% → 20% (bao gồm cả instruction text)
        (width * 0.98f).toInt(),
        (height * 0.80f).toInt()    // từ 65% → 80%
    )

    /**
     * Mũi tên phải → — nằm trong vùng dots/navigation
     * Đo từ overlay: arrows ở khoảng y 67-75%
     */
    val rightArrowRelX: Float get() = 0.68f   // từ 0.65 → 0.68 (phải hơn)
    val rightArrowRelY: Float get() = 0.72f   // giữ nguyên

    /** Mũi tên trái ← */
    val leftArrowRelX: Float get() = 0.32f    // từ 0.35 → 0.32
    val leftArrowRelY: Float get() = 0.72f

    /**
     * Nút Submit — phía dưới arrows
     * Đo từ overlay: submit ≈ y 82-90%
     */
    val submitRelX: Float get() = 0.50f
    val submitRelY: Float get() = 0.86f       // từ 0.84 → 0.86

    /**
     * Vùng quét màu xanh detect nút Submit
     * Mở rộng để bắt chắc hơn
     */
    val submitButtonRect: Rect get() = Rect(
        (width * 0.10f).toInt(),
        (height * 0.75f).toInt(),   // từ 78% → 75%
        (width * 0.90f).toInt(),
        (height * 0.92f).toInt()    // từ 90% → 92%
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
