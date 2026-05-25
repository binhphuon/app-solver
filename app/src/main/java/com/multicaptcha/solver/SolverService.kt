package com.multicaptcha.solver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.multicaptcha.solver.core.DebugLogger
import com.multicaptcha.solver.core.OverlayManager
import com.multicaptcha.solver.core.RootShell
import com.multicaptcha.solver.core.ScreenCapture
import com.multicaptcha.solver.core.WindowLayoutManager
import com.multicaptcha.solver.solver.CaptchaSolver
import com.multicaptcha.solver.solver.FuncaptchaSolver
import com.multicaptcha.solver.solver.OmoApiClient
import com.multicaptcha.solver.solver.SlotManager
import com.multicaptcha.solver.solver.SlotState
import com.multicaptcha.solver.solver.TgSolveApiClient
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class SolverService : Service() {

    private val TAG        = "SolverService"
    private val CHANNEL_ID = "solver_channel"
    private val NOTIF_ID   = 1

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var solverJob: Job? = null

    // ── Lifecycle ────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Đang khởi động..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSolving(intent)
            ACTION_STOP  -> stopSolving()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        DebugLogger.stop()
        super.onDestroy()
    }

    // ── Start / Stop ─────────────────────────────────────────────

    private fun startSolving(intent: Intent) {
        val apiKey   = intent.getStringExtra(EXTRA_API_KEY) ?: ""
        val packages = intent.getStringArrayListExtra(EXTRA_PACKAGES) ?: arrayListOf()
        val provider = intent.getStringExtra(EXTRA_PROVIDER) ?: "omo"

        if (apiKey.isBlank() || packages.isEmpty()) {
            DebugLogger.e(TAG, "Missing apiKey or packages — stopping")
            stopSelf()
            return
        }
        this.provider = provider

        DebugLogger.start()
        DebugLogger.i(TAG, "Service started")
        DebugLogger.i(TAG, "API key: ${apiKey.take(8)}...${apiKey.takeLast(4)}")
        DebugLogger.i(TAG, "Packages (${packages.size}): ${packages.joinToString()}")
        DebugLogger.i(TAG, "Root check: ${if (RootShell.hasRoot()) "OK ✓" else "FAILED ✗"}")

        // Khởi overlay
        OverlayManager.init(this, packages.size)
        OverlayManager.updateHeader("● MultiCaptcha Solver — Đang xếp windows...")

        // Tự động xếp windows vào đúng vị trí trước khi solve
        DebugLogger.sep("AUTO LAYOUT WINDOWS")
        WindowLayoutManager.layoutWindows(packages, this)
        DebugLogger.i(TAG, "Window layout done — solver starting in 1s")
        // Delay nhỏ để user thấy overlay trước khi loop bắt đầu

        solverJob?.cancel()
        solverJob = scope.launch {
            runSolverLoop(apiKey, packages)
        }
    }

    private fun stopSolving() {
        DebugLogger.i(TAG, "Service stopping by user request")
        OverlayManager.updateHeader("■ MultiCaptcha Solver — Stopped")
        solverJob?.cancel()
        OverlayManager.destroy()
        DebugLogger.stop()
        stopSelf()
    }

    // ── Main loop ────────────────────────────────────────────────

    private val solvedTotal = AtomicInteger(0)
    @Volatile private var provider: String = "omo"

    private suspend fun runSolverLoop(apiKey: String, packages: List<String>) {
        // Lấy kích thước màn hình thực
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        // Luôn dùng landscape: chiều dài = screenW, chiều ngắn = screenH
        val screenW = maxOf(dm.widthPixels, dm.heightPixels)
        val screenH = minOf(dm.widthPixels, dm.heightPixels)

        DebugLogger.i(TAG, "Screen raw=${dm.widthPixels}x${dm.heightPixels} → landscape=${screenW}x${screenH} density=${dm.density}")

        val slots     = SlotManager.buildSlots(screenW, screenH, packages)
        val apiClient: CaptchaSolver = when (provider.lowercase()) {
            "tgsolve" -> TgSolveApiClient(apiKey)
            else      -> OmoApiClient(apiKey)
        }
        DebugLogger.i(TAG, "Solver provider: ${apiClient.providerName}")
        val solvers   = slots.map { FuncaptchaSolver(it, apiClient) }

        // Truyền slot info cho debug overlay để vẽ zone lên màn hình
        OverlayManager.setSlots(slots)

        // Log slot config
        slots.forEach { s ->
            DebugLogger.i(TAG, "Slot[${s.index}] pkg=${s.packageName} " +
                "x=${s.xOffset}..${s.xOffset + s.width} w=${s.width} h=${s.height}")
            DebugLogger.d(TAG, "  startBtn rect: ${s.startButtonRect}")
            DebugLogger.d(TAG, "  submitBtn rect: ${s.submitButtonRect}")
            DebugLogger.d(TAG, "  matchThis rect: ${s.matchThisImageRect}")
            DebugLogger.d(TAG, "  rightArrow: rel(${s.rightArrowRelX}, ${s.rightArrowRelY})")
            DebugLogger.d(TAG, "  submit:     rel(${s.submitRelX}, ${s.submitRelY})")
        }

        updateNotification("Running — ${packages.size} slots (parallel)")
        OverlayManager.updateHeader("● MultiCaptcha Solver — Parallel (3 slots)")
        solvedTotal.set(0)

        // ── Launch per-slot coroutines song song ──
        // Mỗi slot có 1 coroutine riêng, poll + solve độc lập → tốc độ tăng ~3x
        // (so với sequential trước đây).
        coroutineScope {
            solvers.forEach { solver ->
                launch { runSlotLoop(solver) }
            }
        }

        DebugLogger.i(TAG, "Solver ended. Total solved across all slots: ${solvedTotal.get()}")
    }

    /**
     * Loop riêng cho từng slot — chạy song song với các slot khác.
     * ScreenCapture.capture() đã thread-safe (unique temp file mỗi call).
     */
    private suspend fun runSlotLoop(solver: FuncaptchaSolver) {
        val idx = solver.slot.index
        var iter = 0
        DebugLogger.i(TAG, "Slot[$idx] loop started")

        while (currentCoroutineContext().isActive) {
            iter++
            val tStart = System.currentTimeMillis()

            try {
                val screenshot = ScreenCapture.capture()
                if (screenshot == null) {
                    DebugLogger.w(TAG, "Slot[$idx] screenshot null — retry in 2s")
                    delay(2000)
                    continue
                }

                val slotBmp = ScreenCapture.cropSlot(screenshot, solver.slot)
                val state   = solver.detectState(slotBmp)
                OverlayManager.updateSlot(idx, state)

                when (state) {
                    SlotState.START_VISIBLE -> {
                        DebugLogger.slotAction(idx, "→ handleStart()")
                        OverlayManager.updateSlotStep(idx, "Tapping Start...")
                        solver.handleStart()
                    }
                    SlotState.TRY_AGAIN -> {
                        DebugLogger.slotAction(idx, "→ handleTryAgain() (captcha sai, tap Try Again)")
                        solver.handleTryAgain()
                    }
                    SlotState.PUZZLE_ACTIVE -> {
                        DebugLogger.slotAction(idx, "→ solvePuzzle()")
                        OverlayManager.updateSlotStep(idx, "Solving...")
                        val ok = solver.solvePuzzle(screenshot)
                        if (ok) {
                            val total = solvedTotal.incrementAndGet()
                            DebugLogger.i(TAG, "Slot[$idx] ✓ Solved! Global total=$total")
                            updateNotification("Solved: $total")
                            solver.resetState()
                        } else {
                            DebugLogger.w(TAG, "Slot[$idx] solve failed — retry next iter")
                            OverlayManager.updateSlotStep(idx, "Thất bại, retry")
                        }
                    }
                    SlotState.SOLVED, SlotState.VERIFYING, SlotState.IDLE -> {
                        // Không làm gì, chờ next poll
                    }
                }

                val elapsed = System.currentTimeMillis() - tStart
                DebugLogger.d(TAG, "Slot[$idx] iter $iter (${elapsed}ms, state=$state)")
                delay(POLL_INTERVAL_MS)

            } catch (e: CancellationException) {
                DebugLogger.i(TAG, "Slot[$idx] cancelled")
                break
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Slot[$idx] loop error", e)
                delay(3000)
            }
        }
    }

    // ── Notification ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Captcha Solver", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MultiCaptcha Solver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    // ── Constants ─────────────────────────────────────────────────

    companion object {
        const val ACTION_START   = "com.multicaptcha.START"
        const val ACTION_STOP    = "com.multicaptcha.STOP"
        const val EXTRA_API_KEY  = "api_key"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_PROVIDER = "provider"

        /** Default packages dùng cho nút Start trong notification */
        val DEFAULT_PACKAGES = arrayListOf("a.baba", "a.dcdc", "a.fefe")

        // Mỗi slot loop poll mỗi 800ms (nhanh hơn 1500ms cũ vì giờ chạy parallel)
        private const val POLL_INTERVAL_MS = 800L
    }
}
