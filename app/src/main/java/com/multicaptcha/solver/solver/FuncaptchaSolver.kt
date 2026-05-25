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

    // Vị trí carousel hiện tại (1-based). Mỗi puzzle/challenge mới FunCaptcha
    // luôn reset về 1 → solvePuzzle() set = 1 ngay đầu mỗi lần chạy.
    private var carouselPos: Int = 1

    // ── Detect state ─────────────────────────────────────────────

    fun detectState(slotBitmap: Bitmap): SlotState {
        DebugLogger.sep("Slot[${slot.index}] detect — ${slotBitmap.width}x${slotBitmap.height}")

        // 1. Nút Start Puzzle (chỉ hiện 1 lần ở đầu captcha session, sau đó các challenge tiếp theo
        //    của cùng session tự load không qua Start button)
        val startRect  = slot.startButtonRect
        val startGreen = ScreenCapture.greenRatio(slotBitmap, startRect)
        val startHit   = startGreen > GREEN_THRESHOLD
        DebugLogger.greenDetect(slot.index, "startBtn[${startRect.left},${startRect.top}-${startRect.right},${startRect.bottom}]",
            startGreen, GREEN_THRESHOLD, startHit)

        if (startHit) {
            DebugLogger.slotState(slot.index, "START_VISIBLE")
            return SlotState.START_VISIBLE
        }

        // 2. Nút Submit green → puzzle đang active. Áp dụng cho MỌI challenge trong session,
        //    không phải chỉ challenge đầu.
        val submitRect  = slot.submitButtonRect
        val submitGreen = ScreenCapture.greenRatio(slotBitmap, submitRect)
        val submitHit   = submitGreen > GREEN_THRESHOLD
        DebugLogger.greenDetect(slot.index, "submitBtn[${submitRect.left},${submitRect.top}-${submitRect.right},${submitRect.bottom}]",
            submitGreen, GREEN_THRESHOLD, submitHit)

        if (submitHit) {
            DebugLogger.slotState(slot.index, "PUZZLE_ACTIVE(submit-green)")
            return SlotState.PUZZLE_ACTIVE
        }

        // 3. Không có start/submit green → IDLE (loading, đã solved xong, hoặc không phải captcha screen)
        DebugLogger.slotState(slot.index, "IDLE")
        return SlotState.IDLE
    }

    // ── Step 1: Tap Start Puzzle ──────────────────────────────────

    suspend fun handleStart() {
        DebugLogger.sep("Slot[${slot.index}] HANDLE START")
        DebugLogger.slotAction(slot.index, "Tap 'Start Puzzle' button")
        OverlayManager.updateSlotStep(slot.index, "Tap Start Puzzle...")

        TouchInjector.tapInSlot(slot, slot.startButtonRelX, slot.startButtonRelY, "Start Puzzle")
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

        // FunCaptcha luôn show challenge mới với carousel ở vị trí 1 → reset internal counter.
        // (Nếu retry sau khi fail giữa chừng, internal pos có thể không khớp real, chấp nhận.)
        carouselPos = 1

        // ── 1. Đọc question text + total options bằng OCR ──────
        // FunCaptcha render bằng SurfaceView → accessibility KHÔNG đọc được nội dung,
        // chỉ OCR mới lấy được text "Using the arrows, move the person... (1 of 5)"
        val slotBmpFirst = ScreenCapture.cropSlot(fullScreenshot, slot)
        val questionBmp  = ScreenCapture.cropRegion(slotBmpFirst, slot.questionTextRect)

        OverlayManager.updateSlotStep(slot.index, "OCR đọc câu hỏi...")
        val ocrText = OcrHelper.extractText(questionBmp)
        DebugLogger.i(TAG, "════ OCR raw: \"${ocrText ?: "<null>"}\" (rect=${slot.questionTextRect})")

        // Parse "(N of M)" — challenge counter. OCR (ML Kit / EasyOCR) hay miss số 1 nhỏ
        // → regex lenient `\d*` cho N (cho phép vắng): "( of 5)" cũng match.
        val challengeMatch = ocrText?.let {
            Regex("""\(\s*(\d*)\s*of\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE).find(it)
        }
        if (challengeMatch != null) {
            val n = challengeMatch.groupValues[1].ifEmpty { "?" }
            DebugLogger.i(TAG, "════ Challenge $n/${challengeMatch.groupValues[2]} (info only)")
        }

        // Loại bỏ phần "(N of M)" khỏi question text gửi API (regex lenient \d*)
        val cleanedOcr = ocrText
            ?.replace(Regex("""\(\s*\d*\s*of\s*\d+\s*\)""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()

        val questionText = cleanedOcr?.takeIf { it.length >= 10 }   // OCR có thể đọc rác → cần >=10 ký tự
            ?: SolverConfig.captchaOther.ifBlank { null }

        DebugLogger.i(TAG, "════ Question text gửi API: \"${questionText ?: "<empty>"}\"")

        // ── Đếm dots (carousel indicator phía trên Submit) — NGUỒN DUY NHẤT cho tổng options ──
        val dotsBmp   = ScreenCapture.cropRegion(slotBmpFirst, slot.dotsCountRect)
        val dotsCount = ScreenCapture.countDots(dotsBmp)
        DebugLogger.i(TAG, "════ Dot count: $dotsCount (rect=${slot.dotsCountRect})")

        // Cập nhật info overlay (luôn hiện, ngoài vùng làm việc)
        OverlayManager.updateSlotInfo(slot.index, questionText, dotsCount)

        // Tổng options: CHỈ dùng dot count
        val totalKnown = dotsCount.takeIf { it in 2..30 }
        if (totalKnown == null) {
            DebugLogger.e(TAG, "Dot count = $dotsCount không hợp lệ (cần 2-30). Abort solve.")
            OverlayManager.updateSlotStep(slot.index, "Lỗi: dot count không hợp lệ")
            currentState = SlotState.PUZZLE_ACTIVE
            return false
        }
        DebugLogger.i(TAG, "════ Total options: $totalKnown (từ dot count)")

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
        // Arkose native: mỗi cell 200×200 vuông. Reference crop của ta thường hẹp
        // hơn option crop (tall:thin) → cần PAD ref width = option width để khi resize
        // lên 3200×400 (=N×200×400) thì ref cũng đúng 200×200, không bị stretch ngang.
        OverlayManager.updateSlotStep(slot.index, "Ghép $totalOptions ảnh...")
        val optionsStrip = ScreenCapture.stitchHorizontal(optionBitmaps)

        // Pad ref to match option dimensions (option_w × option_h)
        val optW = optionBitmaps[0].width
        val optH = optionBitmaps[0].height
        val refPadded = if (refBmp.width != optW || refBmp.height != optH) {
            val p = android.graphics.Bitmap.createBitmap(optW, optH, android.graphics.Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(p)
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(refBmp, 0f, 0f, null)   // ref ở top-left, rest black
            DebugLogger.d(TAG, "Ref padded: ${refBmp.width}x${refBmp.height} → ${p.width}x${p.height}")
            p
        } else refBmp

        val combined = ScreenCapture.stackVertical(optionsStrip, refPadded)
        DebugLogger.d(TAG, "Combined image: ${combined.width}x${combined.height}")

        // ── 3b. Resize về kích thước Arkose native (N × 200 × 400) ──
        // Lý do: OMO/XEvil model trained trên ảnh Arkose original
        //        (mỗi option 200×200, ref 200×200 ở bottom-left).
        //        Composite ta tạo từ screenshot có kích thước nhỏ hơn nhiều
        //        → resize lên đúng spec làm API accept và trả index correct.
        // Test confirmed: 2144×274 → API fail, resize 3200×400 → "score: 100"
        val targetW = totalOptions * 200
        val targetH = 400
        val finalImg = android.graphics.Bitmap.createScaledBitmap(combined, targetW, targetH, true)
        DebugLogger.i(TAG, "Resized to Arkose native: ${finalImg.width}x${finalImg.height}")

        // Lưu ảnh final ra /Download/solver_image/ để debug (trước khi base64)
        val ts = System.currentTimeMillis()
        ScreenCapture.saveDebugImage(finalImg, "slot${slot.index}_${ts}_n${totalOptions}.png")

        val imageB64 = ScreenCapture.toBase64Png(finalImg)   // PNG như OMO dùng

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
        // Sau capture loop carouselPos = totalKnown (last position).
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
        DebugLogger.d(TAG, "resetState → IDLE")
        currentState = SlotState.IDLE
        carouselPos  = 1
    }

    companion object {
        private const val GREEN_THRESHOLD  = 0.06f
        private const val ARROW_DELAY_MS   = 400L
        private const val SUBMIT_DELAY_MS  = 600L
        private const val CAPTURE_DELAY_MS = 650L   // chờ animation scroll trước khi chụp
    }
}
