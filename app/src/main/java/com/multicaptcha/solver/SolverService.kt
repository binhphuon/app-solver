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
import com.multicaptcha.solver.solver.FuncaptchaSolver
import com.multicaptcha.solver.solver.OmoApiClient
import com.multicaptcha.solver.solver.SlotManager
import com.multicaptcha.solver.solver.SlotState
import kotlinx.coroutines.*

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

        if (apiKey.isBlank() || packages.isEmpty()) {
            DebugLogger.e(TAG, "Missing apiKey or packages — stopping")
            stopSelf()
            return
        }

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
        val apiClient = OmoApiClient(apiKey)
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

        updateNotification("Running — ${packages.size} slots")
        OverlayManager.updateHeader("● MultiCaptcha Solver — Running")

        // Dismiss popup khởi động (App Cloner "old version" warning, v.v.)
        DebugLogger.sep("DISMISS STARTUP DIALOGS")
        OverlayManager.updateHeader("⏳ Dismiss dialogs...")
        delay(1200)
        dismissAnyDialog(screenW, screenH)
        delay(1000)

        var loopCount      = 0
        var solvedTotal    = 0
        var allIdleStreak  = 0   // đếm loop liên tiếp tất cả slot đều IDLE

        while (currentCoroutineContext().isActive) {
            val loopStart = System.currentTimeMillis()
            loopCount++

            try {
                DebugLogger.sep("LOOP #$loopCount")
                OverlayManager.updateLoop(loopCount, solvedTotal, 0, solvers.size)

                // Chụp 1 lần cho tất cả slot
                val screenshot = ScreenCapture.capture()
                if (screenshot == null) {
                    DebugLogger.w(TAG, "Screenshot null — skip loop #$loopCount")
                    OverlayManager.updateHeader("⚠ Screenshot failed — retrying...")
                    delay(2000)
                    continue
                }

                var activeCount = 0

                for (solver in solvers) {
                    if (!currentCoroutineContext().isActive) break

                    val slotBmp = ScreenCapture.cropSlot(screenshot, solver.slot)
                    val state   = solver.detectState(slotBmp)
                    OverlayManager.updateSlot(solver.slot.index, state)

                    when (state) {
                        SlotState.START_VISIBLE -> {
                            activeCount++
                            DebugLogger.slotAction(solver.slot.index, "→ handleStart()")
                            OverlayManager.updateSlotStep(solver.slot.index, "Tapping Start...")
                            solver.handleStart()
                        }
                        SlotState.PUZZLE_ACTIVE -> {
                            activeCount++
                            DebugLogger.slotAction(solver.slot.index, "→ solvePuzzle()")
                            OverlayManager.updateSlotStep(solver.slot.index, "Crop ảnh...")
                            val ok = solver.solvePuzzle(screenshot)
                            if (ok) {
                                solvedTotal++
                                DebugLogger.i(TAG, "✓ Solved! Total=$solvedTotal")
                                updateNotification("Solved: $solvedTotal | Loop: $loopCount")
                                OverlayManager.updateLoop(loopCount, solvedTotal, activeCount, solvers.size)
                            } else {
                                DebugLogger.w(TAG, "Solve attempt failed for slot[${solver.slot.index}]")
                                OverlayManager.updateSlotStep(solver.slot.index, "Thất bại, thử lại")
                            }
                        }
                        SlotState.SOLVED -> {
                            solver.resetState()
                            DebugLogger.d(TAG, "Slot[${solver.slot.index}] SOLVED → reset to IDLE")
                        }
                        SlotState.VERIFYING -> {
                            DebugLogger.d(TAG, "Slot[${solver.slot.index}] still VERIFYING — skip")
                        }
                        SlotState.IDLE -> {
                            DebugLogger.d(TAG, "Slot[${solver.slot.index}] IDLE")
                        }
                    }
                }

                val elapsed = System.currentTimeMillis() - loopStart
                DebugLogger.loopTick(loopCount, activeCount, solvers.size, elapsed)
                OverlayManager.updateLoop(loopCount, solvedTotal, activeCount, solvers.size)

                // Nếu tất cả slot đều IDLE quá lâu → có thể đang bị popup che
                if (activeCount == 0) {
                    allIdleStreak++
                    if (allIdleStreak % IDLE_DISMISS_EVERY == 0) {
                        DebugLogger.w(TAG, "All IDLE for $allIdleStreak loops — attempting dialog dismiss")
                        OverlayManager.updateHeader("⚠ All IDLE — dismiss dialog...")
                        dismissAnyDialog(screenW, screenH)
                        delay(800)
                        OverlayManager.updateHeader("● MultiCaptcha Solver — Running")
                    }
                } else {
                    allIdleStreak = 0
                }

                delay(POLL_INTERVAL_MS)

            } catch (e: CancellationException) {
                DebugLogger.i(TAG, "Loop cancelled")
                break
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Unexpected loop error", e)
                delay(3000)
            }
        }

        DebugLogger.i(TAG, "Solver loop ended. Total solved: $solvedTotal in $loopCount loops")
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

    // ── Dismiss popup / dialog ────────────────────────────────────

    /**
     * Dismiss bất kỳ popup/dialog nào đang che màn hình bằng cách:
     * 1. Tap vào góc ngoài cùng của màn hình (ngoài bất kỳ dialog nào centered)
     * 2. Gửi BACK key (đóng dialog cancelable)
     *
     * Dialog "This app was built for older Android" của App Cloner
     * thường nằm ở trung tâm màn hình, nên tap góc (30, 50) sẽ dismiss nó.
     */
    private suspend fun dismissAnyDialog(screenW: Int, screenH: Int) {
        DebugLogger.d(TAG, "dismissAnyDialog: tap corners + BACK")

        // Tap góc màn hình — nằm ngoài bất kỳ dialog centered nào
        val corners = listOf(
            30 to 50,               // top-left
            screenW - 30 to 50,    // top-right
            30 to screenH - 50     // bottom-left
        )
        for ((x, y) in corners) {
            RootShell.execSilent("input tap $x $y")
            delay(220)
        }

        // BACK key — đóng dialog cancelable
        RootShell.execSilent("input keyevent 4")
        DebugLogger.d(TAG, "dismissAnyDialog: done")
    }

    // ── Constants ─────────────────────────────────────────────────

    companion object {
        const val ACTION_START   = "com.multicaptcha.START"
        const val ACTION_STOP    = "com.multicaptcha.STOP"
        const val EXTRA_API_KEY  = "api_key"
        const val EXTRA_PACKAGES = "packages"
        private const val POLL_INTERVAL_MS    = 1500L
        private const val IDLE_DISMISS_EVERY  = 12   // dismiss sau 12 loop IDLE (~18s)
    }
}
