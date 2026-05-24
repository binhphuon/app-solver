package com.multicaptcha.solver.solver

import android.graphics.Bitmap
import com.multicaptcha.solver.core.DebugLogger
import com.multicaptcha.solver.core.OverlayManager
import com.multicaptcha.solver.core.ScreenCapture
import com.multicaptcha.solver.core.TouchInjector
import kotlinx.coroutines.delay

enum class SlotState {
    IDLE,
    START_VISIBLE,
    PUZZLE_ACTIVE,
    VERIFYING,
    SOLVED
}

class FuncaptchaSolver(
    val slot: SlotConfig,
    private val apiClient: OmoApiClient
) {
    private val TAG = "Solver[${slot.index}]"

    var currentState: SlotState = SlotState.IDLE
        private set

    // true sau khi handleStart() được gọi — ngăn solvePuzzle() chạy khi window chưa mở
    private var hasSeenStart: Boolean = false

    // ── Detect state ─────────────────────────────────────────────

    fun detectState(slotBitmap: Bitmap): SlotState {
        DebugLogger.sep("Slot[${slot.index}] detect — ${slotBitmap.width}x${slotBitmap.height}")

        // 1. Check nút Start Puzzle (green vùng giữa-dưới)
        val startRect  = slot.startButtonRect
        val startGreen = ScreenCapture.greenRatio(slotBitmap, startRect)
        val startHit   = startGreen > GREEN_THRESHOLD
        DebugLogger.greenDetect(slot.index, "startBtn[${startRect.left},${startRect.top}-${startRect.right},${startRect.bottom}]",
            startGreen, GREEN_THRESHOLD, startHit)

        if (startHit) {
            DebugLogger.slotState(slot.index, "START_VISIBLE")
            return SlotState.START_VISIBLE
        }

        // 2. Check nút Submit (green vùng dưới) → puzzle đang active và sẵn sàng submit
        val submitRect  = slot.submitButtonRect
        val submitGreen = ScreenCapture.greenRatio(slotBitmap, submitRect)
        val submitHit   = submitGreen > GREEN_THRESHOLD
        DebugLogger.greenDetect(slot.index, "submitBtn[${submitRect.left},${submitRect.top}-${submitRect.right},${submitRect.bottom}]",
            submitGreen, GREEN_THRESHOLD, submitHit)

        if (submitHit) {
            DebugLogger.slotState(slot.index, "PUZZLE_ACTIVE(submit-green)")
            return SlotState.PUZZLE_ACTIVE
        }

        // 3. hasContent fallback — chỉ dùng sau khi đã từng thấy START_VISIBLE
        //    (tránh call API khi window vừa mở, chưa phải captcha)
        val challengeArea = ScreenCapture.cropRegion(slotBitmap, slot.challengeImageRect)
        val hasContent    = ScreenCapture.hasSignificantContent(challengeArea)
        DebugLogger.d(TAG, "hasContent=$hasContent hasSeenStart=$hasSeenStart " +
            "(challengeRect=[${slot.challengeImageRect.left},${slot.challengeImageRect.top}-${slot.challengeImageRect.right},${slot.challengeImageRect.bottom}])")

        val state = when {
            hasContent && hasSeenStart -> SlotState.PUZZLE_ACTIVE
            else                       -> SlotState.IDLE
        }
        DebugLogger.slotState(slot.index, "${state.name}(hasContent=$hasContent,hasSeenStart=$hasSeenStart)")
        return state
    }

    // ── Step 1: Tap Start Puzzle ──────────────────────────────────

    suspend fun handleStart() {
        DebugLogger.sep("Slot[${slot.index}] HANDLE START")
        DebugLogger.slotAction(slot.index, "Tap 'Start Puzzle' button")
        OverlayManager.updateSlotStep(slot.index, "Tap Start Puzzle...")

        TouchInjector.tapInSlot(slot, slot.startButtonRelX, slot.startButtonRelY, "Start Puzzle")
        hasSeenStart = true
        currentState = SlotState.PUZZLE_ACTIVE

        OverlayManager.updateSlotStep(slot.index, "Chờ puzzle load...")
        DebugLogger.d(TAG, "Waiting 2500ms for puzzle to load...")
        delay(2500)
    }

    // ── Step 2: Solve match-game puzzle ──────────────────────────

    suspend fun solvePuzzle(fullScreenshot: Bitmap): Boolean {
        DebugLogger.sep("Slot[${slot.index}] SOLVE PUZZLE")
        currentState = SlotState.VERIFYING

        // 1. Crop ảnh "Match This!"
        OverlayManager.updateSlotStep(slot.index, "Crop ảnh Match This...")
        DebugLogger.d(TAG, "Cropping Match This! image — rect=${slot.matchThisImageRect}")
        val slotBmp  = ScreenCapture.cropSlot(fullScreenshot, slot)
        val refBmp   = ScreenCapture.cropRegion(slotBmp, slot.matchThisImageRect)
        val imageB64 = ScreenCapture.toBase64(refBmp)

        if (imageB64.isEmpty()) {
            DebugLogger.e(TAG, "imageBase64 is empty — cannot send to API")
            OverlayManager.updateSlotStep(slot.index, "Lỗi: không encode được ảnh")
            currentState = SlotState.PUZZLE_ACTIVE
            return false
        }

        // 2. Gửi API
        OverlayManager.updateSlot(slot.index, SlotState.VERIFYING, "Gửi API...")
        DebugLogger.d(TAG, "Sending to omocaptcha API...")
        val taskId = apiClient.createTask(imageB64, slotIdx = slot.index) ?: run {
            DebugLogger.apiError(slot.index, "createTask returned null")
            OverlayManager.updateSlotStep(slot.index, "Lỗi: createTask thất bại")
            currentState = SlotState.PUZZLE_ACTIVE
            return false
        }

        // 3. Poll kết quả
        OverlayManager.updateSlotStep(slot.index, "Chờ kết quả API...")
        val result = apiClient.getTaskResult(taskId, slotIdx = slot.index) ?: run {
            DebugLogger.apiError(slot.index, "getTaskResult returned null — taskId=$taskId")
            OverlayManager.updateSlotStep(slot.index, "Lỗi: timeout API")
            currentState = SlotState.PUZZLE_ACTIVE
            return false
        }

        // 4. Tính số lần click mũi tên (index 1-based → clicks = index-1)
        val clickCount = (result.index - 1).coerceAtLeast(0)
        DebugLogger.d(TAG, "Answer: index=${result.index} → right arrow clicks=$clickCount")

        // 5. Click mũi tên phải
        if (clickCount == 0) {
            DebugLogger.slotAction(slot.index, "index=1, không cần click mũi tên")
            OverlayManager.updateSlotStep(slot.index, "Vị trí đúng, skip arrow")
        } else {
            OverlayManager.updateSlot(slot.index, SlotState.PUZZLE_ACTIVE, "Click → ×$clickCount")
            for (i in 1..clickCount) {
                if (i > 1) delay(ARROW_DELAY_MS)
                TouchInjector.tapInSlot(slot, slot.rightArrowRelX, slot.rightArrowRelY, "→ arrow")
                DebugLogger.arrowClick(slot.index, i, clickCount)
                OverlayManager.updateSlotStep(slot.index, "→ arrow $i/$clickCount")
            }
        }

        // 6. Submit
        OverlayManager.updateSlotStep(slot.index, "Submit...")
        DebugLogger.d(TAG, "Waiting ${SUBMIT_DELAY_MS}ms before Submit...")
        delay(SUBMIT_DELAY_MS)

        DebugLogger.slotAction(slot.index, "Tap Submit button")
        TouchInjector.tapInSlot(slot, slot.submitRelX, slot.submitRelY, "Submit")

        currentState = SlotState.SOLVED
        OverlayManager.updateSlot(slot.index, SlotState.SOLVED, "✓ Done!")
        DebugLogger.i(TAG, "Puzzle submitted — waiting for result...")
        delay(1500)

        return true
    }

    fun resetState() {
        DebugLogger.d(TAG, "resetState SOLVED→IDLE")
        currentState = SlotState.IDLE
        hasSeenStart = false
    }

    companion object {
        private const val GREEN_THRESHOLD = 0.06f
        private const val ARROW_DELAY_MS  = 400L
        private const val SUBMIT_DELAY_MS = 600L
    }
}
