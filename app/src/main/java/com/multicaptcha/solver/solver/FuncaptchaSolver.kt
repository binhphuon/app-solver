package com.multicaptcha.solver.solver

import android.graphics.Bitmap
import com.multicaptcha.solver.SolverConfig
import com.multicaptcha.solver.core.DebugLogger
import com.multicaptcha.solver.core.OcrHelper
import com.multicaptcha.solver.core.OverlayManager
import com.multicaptcha.solver.core.ScreenCapture
import com.multicaptcha.solver.core.SolverAccessibilityService
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

    // Vị trí carousel hiện tại (1-based, reset khi puzzle mới load)
    private var carouselPos: Int = 1

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
        carouselPos  = 1          // puzzle mới → carousel reset về position 1
        currentState = SlotState.PUZZLE_ACTIVE

        OverlayManager.updateSlotStep(slot.index, "Chờ puzzle load...")
        DebugLogger.d(TAG, "Waiting 2500ms for puzzle to load...")
        delay(2500)
    }

    // ── Step 2: Solve match-game puzzle ──────────────────────────

    suspend fun solvePuzzle(fullScreenshot: Bitmap): Boolean {
        DebugLogger.sep("Slot[${slot.index}] SOLVE PUZZLE")
        currentState = SlotState.VERIFYING

        // ── 0. Reset carousel về position 1 nếu cần ────────────
        // Vì chưa biết total, click phải MAX_OPTIONS lần = 1 vòng đầy → về lại vị trí ban đầu
        // Sau đó duplicate-detection khi capture sẽ xác định đúng total.
        if (carouselPos != 1) {
            DebugLogger.d(TAG, "Carousel at pos $carouselPos — full revolution reset (${MAX_OPTIONS} clicks)")
            OverlayManager.updateSlotStep(slot.index, "Reset carousel về pos 1...")
            repeat(MAX_OPTIONS) {
                TouchInjector.tapInSlot(slot, slot.rightArrowRelX, slot.rightArrowRelY, "→ reset")
                delay(ARROW_DELAY_MS)
            }
            carouselPos = 1
        }

        // ── 1. Đọc question text + total options bằng OCR ──────
        // FunCaptcha render bằng SurfaceView → accessibility KHÔNG đọc được nội dung,
        // chỉ OCR mới lấy được text "Using the arrows, move the person... (1 of 5)"
        val slotBmpFirst = ScreenCapture.cropSlot(fullScreenshot, slot)
        val questionBmp  = ScreenCapture.cropRegion(slotBmpFirst, slot.questionTextRect)

        OverlayManager.updateSlotStep(slot.index, "OCR đọc câu hỏi...")
        val ocrText = OcrHelper.extractText(questionBmp)
        DebugLogger.i(TAG, "════ OCR raw: \"${ocrText ?: "<null>"}\" (rect=${slot.questionTextRect})")

        // Parse "(N of M)" để biết total options luôn nếu có
        val ocrTotal = ocrText?.let {
            Regex("""\(\s*\d+\s*of\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE).find(it)
                ?.groupValues?.get(1)?.toIntOrNull()
        }
        DebugLogger.i(TAG, "════ OCR counter total: ${ocrTotal ?: "<not found>"}")

        // Loại bỏ phần "(N of M)" khỏi question text gửi API
        val cleanedOcr = ocrText
            ?.replace(Regex("""\(\s*\d+\s*of\s*\d+\s*\)""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()

        val questionText = cleanedOcr?.takeIf { it.length >= 10 }   // OCR có thể đọc rác → cần >=10 ký tự
            ?: SolverConfig.captchaOther.ifBlank { null }

        DebugLogger.i(TAG, "════ Question text gửi API: \"${questionText ?: "<empty>"}\"")

        // ── Đếm dots (carousel indicator phía trên Submit) ──────
        val dotsBmp   = ScreenCapture.cropRegion(slotBmpFirst, slot.dotsCountRect)
        val dotsCount = ScreenCapture.countDots(dotsBmp)
        DebugLogger.i(TAG, "════ Dot count: $dotsCount (rect=${slot.dotsCountRect})")

        // Cập nhật info overlay (luôn hiện, ngoài vùng làm việc)
        OverlayManager.updateSlotInfo(slot.index, questionText, dotsCount)

        // Tổng options: ưu tiên OCR "(N of M)" → fallback đếm dots
        val totalKnown = ocrTotal ?: dotsCount.takeIf { it in 2..30 }
        if (totalKnown == null) {
            DebugLogger.e(TAG, "Không xác định được tổng options (OCR + dot count đều fail). Abort.")
            OverlayManager.updateSlotStep(slot.index, "Lỗi: không biết tổng options")
            currentState = SlotState.PUZZLE_ACTIVE
            return false
        }
        DebugLogger.i(TAG, "════ Total options dùng: $totalKnown (source=${if (ocrTotal != null) "OCR" else "dots"})")

        // ── Crop reference image từ screenshot hiện tại ──────────
        val refBmp = ScreenCapture.cropRegion(slotBmpFirst, slot.matchThisImageRect)
        DebugLogger.d(TAG, "Reference rect=${slot.matchThisImageRect}")

        // ── 2. Capture tất cả options bằng cách scroll (số lượng dynamic) ──
        val optionBitmaps = mutableListOf<Bitmap>()

        // Option 1: từ screenshot ban đầu (không cần chụp lại)
        val firstOption = ScreenCapture.cropRegion(slotBmpFirst, slot.currentOptionRect)
        optionBitmaps.add(firstOption)
        DebugLogger.d(TAG, "Captured option 1 (from existing screenshot)")

        // Options 2..N: scroll → chụp đúng totalKnown lần (không dùng dedup nữa)
        for (i in 2..totalKnown) {
            OverlayManager.updateSlotStep(slot.index, "Chụp option $i/$totalKnown...")
            TouchInjector.tapInSlot(slot, slot.rightArrowRelX, slot.rightArrowRelY, "→ capture $i")
            carouselPos = i
            delay(CAPTURE_DELAY_MS)

            val shot = ScreenCapture.capture()
            if (shot == null) {
                DebugLogger.w(TAG, "Screenshot null at option $i — using previous")
                optionBitmaps.add(optionBitmaps.last())
                continue
            }
            val slotBmpI  = ScreenCapture.cropSlot(shot, slot)
            val newOption = ScreenCapture.cropRegion(slotBmpI, slot.currentOptionRect)
            optionBitmaps.add(newOption)
            DebugLogger.d(TAG, "Captured option $i/$totalKnown")
        }

        val totalOptions = optionBitmaps.size
        DebugLogger.i(TAG, "Captured $totalOptions options total")

        // ── 3. Ghép ảnh: options ngang + reference bên dưới ─────
        OverlayManager.updateSlotStep(slot.index, "Ghép $totalOptions ảnh...")
        val optionsStrip = ScreenCapture.stitchHorizontal(optionBitmaps)
        val combined     = ScreenCapture.stackVertical(optionsStrip, refBmp)
        DebugLogger.d(TAG, "Combined image: ${combined.width}x${combined.height}")

        // Lưu ảnh final ra /Download/solver_image/ để debug (trước khi base64)
        val ts = System.currentTimeMillis()
        ScreenCapture.saveDebugImage(combined, "slot${slot.index}_${ts}_n${totalOptions}.png")

        val imageB64 = ScreenCapture.toBase64Png(combined)   // PNG như OMO dùng

        if (imageB64.isEmpty()) {
            DebugLogger.e(TAG, "imageBase64 is empty — cannot send to API")
            OverlayManager.updateSlotStep(slot.index, "Lỗi: không encode được ảnh")
            currentState = SlotState.PUZZLE_ACTIVE
            return false
        }

        // ── 4. Gửi API ──────────────────────────────────────────
        OverlayManager.updateSlot(slot.index, SlotState.VERIFYING, "Gửi API...")
        DebugLogger.d(TAG, "Sending combined image to omocaptcha API...")
        val taskId = apiClient.createTask(imageB64, question = questionText ?: "", slotIdx = slot.index) ?: run {
            DebugLogger.apiError(slot.index, "createTask returned null")
            OverlayManager.updateSlotStep(slot.index, "Lỗi: createTask thất bại")
            currentState = SlotState.PUZZLE_ACTIVE
            return false
        }

        // ── 5. Poll kết quả ─────────────────────────────────────
        OverlayManager.updateSlotStep(slot.index, "Chờ kết quả API...")
        val result = apiClient.getTaskResult(taskId, slotIdx = slot.index) ?: run {
            DebugLogger.apiError(slot.index, "getTaskResult returned null — taskId=$taskId")
            OverlayManager.updateSlotStep(slot.index, "Lỗi: timeout API")
            currentState = SlotState.PUZZLE_ACTIVE
            return false
        }

        // ── 6. Navigate đến đúng position ───────────────────────
        // carouselPos hiện tại: nếu detected wrap → 1, nếu hit MAX_OPTIONS → MAX_OPTIONS
        // Clicks cần = (index - carouselPos + totalOptions) % totalOptions
        val clicksNeeded = ((result.index - carouselPos + totalOptions) % totalOptions)
        DebugLogger.d(TAG, "Answer: index=${result.index}, currentPos=$carouselPos, total=$totalOptions → $clicksNeeded clicks needed")

        if (clicksNeeded == 0) {
            DebugLogger.slotAction(slot.index, "Đang ở đúng vị trí (pos=${result.index})")
            OverlayManager.updateSlotStep(slot.index, "Đúng vị trí, không cần scroll")
        } else {
            OverlayManager.updateSlot(slot.index, SlotState.PUZZLE_ACTIVE, "Navigate → ×$clicksNeeded")
            for (i in 1..clicksNeeded) {
                if (i > 1) delay(ARROW_DELAY_MS)
                TouchInjector.tapInSlot(slot, slot.rightArrowRelX, slot.rightArrowRelY, "→ nav $i/$clicksNeeded")
                carouselPos = (carouselPos % totalOptions) + 1
                DebugLogger.arrowClick(slot.index, i, clicksNeeded)
                OverlayManager.updateSlotStep(slot.index, "→ $i/$clicksNeeded (pos=$carouselPos)")
            }
        }

        // ── 7. Submit ────────────────────────────────────────────
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
        carouselPos  = 1
    }

    companion object {
        private const val GREEN_THRESHOLD  = 0.06f
        private const val ARROW_DELAY_MS   = 400L
        private const val SUBMIT_DELAY_MS  = 600L
        private const val CAPTURE_DELAY_MS = 650L   // chờ animation scroll trước khi chụp
        private const val MAX_OPTIONS      = 22      // giới hạn cho carousel reset (FunCaptcha max ~20)
    }
}
