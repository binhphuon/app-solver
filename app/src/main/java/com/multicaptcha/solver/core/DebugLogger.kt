package com.multicaptcha.solver.core

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Debug logger — ghi đồng thời ra Logcat và file
 * Path: /storage/emulated/0/Download/solver_debug.txt
 */
object DebugLogger {

    private const val TAG       = "MCS_Debug"
    private const val LOG_PATH  = "/storage/emulated/0/Download/solver_debug.txt"
    private const val MAX_BYTES = 5 * 1024 * 1024L   // 5 MB rồi rotate

    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val queue = LinkedBlockingQueue<String>(2000)
    private val running = AtomicBoolean(false)
    private var writerThread: Thread? = null

    // ── Khởi động / dừng ────────────────────────────────────────

    fun start() {
        if (running.getAndSet(true)) return
        // Ghi header phiên mới
        val sep = "═".repeat(60)
        queue.offer("\n$sep")
        queue.offer("  SESSION START — ${sdf.format(Date())}")
        queue.offer(sep)

        writerThread = thread(name = "DebugLogger", isDaemon = true) {
            val file = File(LOG_PATH)
            try {
                file.parentFile?.mkdirs()
                while (running.get() || queue.isNotEmpty()) {
                    val line = queue.poll() ?: run {
                        Thread.sleep(100)
                        return@run null
                    } ?: continue

                    // Rotate nếu file quá lớn
                    if (file.exists() && file.length() > MAX_BYTES) rotate(file)

                    FileWriter(file, true).use { it.write(line + "\n") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Writer thread error", e)
            }
        }
    }

    fun stop() {
        i("SYSTEM", "Logger stopping...")
        running.set(false)
        writerThread?.join(3000)
    }

    // ── Log levels ───────────────────────────────────────────────

    /** INFO — trạng thái bình thường */
    fun i(tag: String, msg: String) = write("I", tag, msg).also { Log.i(TAG, "[$tag] $msg") }

    /** DEBUG — chi tiết kỹ thuật */
    fun d(tag: String, msg: String) = write("D", tag, msg).also { Log.d(TAG, "[$tag] $msg") }

    /** WARN — cần chú ý nhưng không crash */
    fun w(tag: String, msg: String) = write("W", tag, msg).also { Log.w(TAG, "[$tag] $msg") }

    /** ERROR — lỗi */
    fun e(tag: String, msg: String, ex: Throwable? = null) {
        val full = if (ex != null) "$msg\n    Exception: ${ex.javaClass.simpleName}: ${ex.message}" else msg
        write("E", tag, full)
        if (ex != null) Log.e(TAG, "[$tag] $msg", ex) else Log.e(TAG, "[$tag] $msg")
    }

    /** SEPARATOR — phân cách visual giữa các block */
    fun sep(label: String = "") {
        val line = if (label.isEmpty()) "─".repeat(55)
                   else "── $label " + "─".repeat((55 - label.length - 4).coerceAtLeast(0))
        write("-", "---", line)
        Log.d(TAG, line)
    }

    // ── Screenshot debug ─────────────────────────────────────────

    fun screenshotResult(success: Boolean, widthPx: Int = 0, heightPx: Int = 0) {
        if (success)
            d("Screenshot", "OK — ${widthPx}x${heightPx}px")
        else
            e("Screenshot", "FAILED — screencap returned null")
    }

    // ── Green detection debug ────────────────────────────────────

    fun greenDetect(slotIdx: Int, region: String, ratio: Float, threshold: Float, detected: Boolean) {
        val bar   = buildBar(ratio, max = 0.5f, width = 20)
        val mark  = if (detected) "✓ HIT" else "✗ miss"
        d("Detect[$slotIdx]", "green[$region] $bar ${pct(ratio)} (thr=${pct(threshold)}) $mark")
    }

    // ── Slot state debug ─────────────────────────────────────────

    fun slotState(slotIdx: Int, state: String) {
        i("Slot[$slotIdx]", "state → $state")
    }

    fun slotAction(slotIdx: Int, action: String) {
        i("Slot[$slotIdx]", "ACTION: $action")
    }

    // ── API debug ────────────────────────────────────────────────

    fun apiCreateTask(slotIdx: Int, imageSizeBytes: Int) {
        d("API[$slotIdx]", "createTask → imageSize=${fmtBytes(imageSizeBytes)}")
    }

    fun apiTaskCreated(slotIdx: Int, taskId: String) {
        i("API[$slotIdx]", "taskId=$taskId")
    }

    fun apiPollResult(slotIdx: Int, attempt: Int, status: String) {
        d("API[$slotIdx]", "poll #$attempt → status=$status")
    }

    fun apiResult(slotIdx: Int, index: Int, clickCount: Int) {
        i("API[$slotIdx]", "RESULT index=$index → clicks=$clickCount")
    }

    fun apiError(slotIdx: Int, reason: String) {
        e("API[$slotIdx]", "ERROR: $reason")
    }

    // ── Touch debug ──────────────────────────────────────────────

    fun tap(slotIdx: Int, absX: Int, absY: Int, label: String) {
        d("Touch[$slotIdx]", "tap($absX, $absY) ← $label")
    }

    fun arrowClick(slotIdx: Int, clickNum: Int, total: Int) {
        d("Touch[$slotIdx]", "→ arrow click $clickNum/$total")
    }

    // ── Loop stats ───────────────────────────────────────────────

    fun loopTick(loop: Int, active: Int, total: Int, elapsedMs: Long) {
        if (loop % 10 == 0) {
            i("Loop", "#$loop | active=$active/$total | tick=${elapsedMs}ms")
        } else {
            d("Loop", "#$loop | active=$active/$total | tick=${elapsedMs}ms")
        }
    }

    // ── Internal ─────────────────────────────────────────────────

    private fun write(level: String, tag: String, msg: String) {
        if (!running.get()) return
        val timestamp = sdf.format(Date())
        val line      = "$timestamp $level/$tag: $msg"
        queue.offer(line)
    }

    private fun rotate(file: File) {
        val backup = File(file.parent, "solver_debug_old.txt")
        backup.delete()
        file.renameTo(backup)
        write("I", "Logger", "Log rotated — old saved to solver_debug_old.txt")
    }

    private fun buildBar(value: Float, max: Float, width: Int): String {
        val filled = ((value / max) * width).toInt().coerceIn(0, width)
        return "[" + "█".repeat(filled) + "░".repeat(width - filled) + "]"
    }

    private fun pct(f: Float) = "${"%.1f".format(f * 100)}%"
    private fun fmtBytes(b: Int) = when {
        b >= 1024 * 1024 -> "${"%.1f".format(b / 1048576f)}MB"
        b >= 1024        -> "${"%.1f".format(b / 1024f)}KB"
        else             -> "${b}B"
    }
}
