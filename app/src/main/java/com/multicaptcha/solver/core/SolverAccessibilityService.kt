package com.multicaptcha.solver.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service để đọc UI của các floating window FunCaptcha.
 * Được grant tự động qua root shell khi app khởi động.
 *
 * Cung cấp:
 *  - getQuestionText(pkg): đọc câu hỏi từ FunCaptcha widget
 *  - getCounterText(pkg):  đọc "1 / 6" để biết tổng số options
 */
class SolverAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG          = "AccessibilitySvc"
        private const val CHANNEL      = "a11y_channel"
        private const val NOTIF_ID     = 42
        const val ACTION_START_SOLVER  = "com.multicaptcha.NOTIF_START"
        const val ACTION_STOP_SOLVER   = "com.multicaptcha.NOTIF_STOP"
        const val ACTION_TOGGLE_OVERLAY= "com.multicaptcha.NOTIF_TOGGLE_OVERLAY"

        @Volatile private var instance: SolverAccessibilityService? = null

        fun isAvailable(): Boolean = instance != null

        /**
         * Đọc câu hỏi của FunCaptcha từ UI.
         * Tìm node có text dài (>20 ký tự) trong cửa sổ thuộc [pkg].
         * Priority: node focusable trong FunCaptcha view → fallback bất kỳ text dài.
         */
        fun getQuestionText(pkg: String): String? {
            val svc = instance ?: return null
            return try {
                val windows = svc.windows ?: return null
                for (window in windows) {
                    val root = window.root ?: continue
                    val winPkg = root.packageName?.toString() ?: ""
                    if (!winPkg.contains(pkg, ignoreCase = true) &&
                        !pkg.contains(winPkg, ignoreCase = true)) {
                        root.recycle(); continue
                    }
                    // Tìm text dài trong FunCaptcha node
                    val text = findQuestionText(root)
                    root.recycle()
                    if (!text.isNullOrBlank()) {
                        DebugLogger.i(TAG, "getQuestionText[$pkg]: \"$text\"")
                        return text
                    }
                }
                DebugLogger.w(TAG, "getQuestionText[$pkg]: not found")
                null
            } catch (e: Exception) {
                DebugLogger.e(TAG, "getQuestionText exception", e)
                null
            }
        }

        /**
         * Đọc counter "1 / 6" để biết tổng số options trong carousel.
         * @return số nguyên (total) nếu đọc được, null nếu không tìm thấy.
         */
        fun getTotalOptions(pkg: String): Int? {
            val svc = instance ?: return null
            return try {
                val windows = svc.windows ?: return null
                for (window in windows) {
                    val root = window.root ?: continue
                    val winPkg = root.packageName?.toString() ?: ""
                    if (!winPkg.contains(pkg, ignoreCase = true) &&
                        !pkg.contains(winPkg, ignoreCase = true)) {
                        root.recycle(); continue
                    }
                    val total = findCounterTotal(root)
                    root.recycle()
                    if (total != null) {
                        DebugLogger.i(TAG, "getTotalOptions[$pkg]: $total")
                        return total
                    }
                }
                DebugLogger.d(TAG, "getTotalOptions[$pkg]: counter not found")
                null
            } catch (e: Exception) {
                DebugLogger.e(TAG, "getTotalOptions exception", e)
                null
            }
        }

        // ── Helpers ───────────────────────────────────────────────

        /**
         * DFS tìm node text dài (câu hỏi captcha).
         * Ưu tiên: focusable + text >20 chars; fallback: text >30 chars.
         */
        private fun findQuestionText(node: AccessibilityNodeInfo): String? {
            val text = node.text?.toString()?.trim() ?: ""
            if (text.length > 20 && node.isFocusable) return text

            var fallback: String? = null
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findQuestionText(child)
                child.recycle()
                if (found != null) {
                    if (found.length > 20 && node.isFocusable) return found  // exact match
                    if (fallback == null && found.length > 30) fallback = found
                }
            }
            // Check this node itself as fallback
            if (fallback == null && text.length > 30) fallback = text
            return fallback
        }

        /**
         * Tìm node có text dạng "N / M" hoặc "N/M" và trả về M (total).
         */
        private fun findCounterTotal(node: AccessibilityNodeInfo): Int? {
            val text = node.text?.toString()?.trim() ?: ""
            // Match "1 / 6", "1/6", "1 of 6", "1 / 20" ...
            val m = Regex("""(\d+)\s*[/of]+\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
            if (m != null) {
                val total = m.groupValues[2].toIntOrNull()
                if (total != null && total in 2..30) return total
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findCounterTotal(child)
                child.recycle()
                if (found != null) return found
            }
            return null
        }
    }

    // ── Service lifecycle ────────────────────────────────────────

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START_SOLVER   -> handleStartSolver()
                ACTION_STOP_SOLVER    -> handleStopSolver()
                ACTION_TOGGLE_OVERLAY -> handleToggleOverlay()
            }
        }

        private fun handleToggleOverlay() {
            OverlayManager.toggleDebugOverlays()
            val state = if (OverlayManager.debugOverlaysVisible) "ON" else "OFF"
            android.widget.Toast.makeText(this@SolverAccessibilityService,
                "👁 Overlay zones: $state", android.widget.Toast.LENGTH_SHORT).show()
            try { RootShell.execSilent("cmd statusbar collapse") } catch (_: Exception) {}
        }

        private fun handleStartSolver() {
            DebugLogger.start()
            // Đọc provider + API key của provider đang chọn từ prefs
            val prefs    = getSharedPreferences("mcs_prefs", Context.MODE_PRIVATE)
            val provider = com.multicaptcha.solver.SolverConfig.solverProvider
            val keyName  = if (provider == "tgsolve") "api_key_tgsolve" else "api_key_omo"
            val apiKey   = prefs.getString(keyName, "")?.trim() ?: ""

            if (apiKey.isBlank()) {
                DebugLogger.e(TAG, "Notif Start: API key cho provider '$provider' chưa nhập")
                android.widget.Toast.makeText(this@SolverAccessibilityService,
                    "⚠ Mở app nhập API key cho $provider!", android.widget.Toast.LENGTH_LONG).show()
                return
            }

            try { RootShell.execSilent("cmd statusbar collapse") } catch (_: Exception) {}

            DebugLogger.i(TAG, "Notif Start: provider=$provider, launching SolverService")
            val intent = Intent(this@SolverAccessibilityService,
                com.multicaptcha.solver.SolverService::class.java).apply {
                action = com.multicaptcha.solver.SolverService.ACTION_START
                putExtra(com.multicaptcha.solver.SolverService.EXTRA_API_KEY, apiKey)
                putExtra(com.multicaptcha.solver.SolverService.EXTRA_PROVIDER, provider)
                putStringArrayListExtra(
                    com.multicaptcha.solver.SolverService.EXTRA_PACKAGES,
                    com.multicaptcha.solver.SolverService.DEFAULT_PACKAGES
                )
            }
            startForegroundService(intent)
            android.widget.Toast.makeText(this@SolverAccessibilityService,
                "▶ Solver khởi động ($provider)...", android.widget.Toast.LENGTH_SHORT).show()
        }

        private fun handleStopSolver() {
            DebugLogger.i(TAG, "Notif Stop: stopping SolverService")
            try { RootShell.execSilent("cmd statusbar collapse") } catch (_: Exception) {}
            val intent = Intent(this@SolverAccessibilityService,
                com.multicaptcha.solver.SolverService::class.java).apply {
                action = com.multicaptcha.solver.SolverService.ACTION_STOP
            }
            startService(intent)
            android.widget.Toast.makeText(this@SolverAccessibilityService,
                "■ Solver đã dừng", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onServiceConnected() {
        // instance được set TRƯỚC MỌI THỨ — ngay cả khi phần sau crash, isAvailable() vẫn đúng
        instance = this

        // Ghi file ngay để biết onServiceConnected() có được gọi không
        try {
            java.io.File("/storage/emulated/0/Download/a11y_connected.txt")
                .writeText("onServiceConnected at ${System.currentTimeMillis()}\npkg=$packageName\n")
        } catch (_: Exception) {}

        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            serviceInfo = info
            DebugLogger.i(TAG, "AccessibilityService connected ✓")
        } catch (e: Exception) {
            DebugLogger.e(TAG, "serviceInfo setup failed", e)
        }

        // Đăng ký BroadcastReceiver nội bộ cho Dump/Start/Stop (API 33+ yêu cầu export flag)
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_START_SOLVER)
                addAction(ACTION_STOP_SOLVER)
                addAction(ACTION_TOGGLE_OVERLAY)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(notifReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(notifReceiver, filter)
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "registerReceiver failed", e)
        }

        // Hiển thị persistent notification với nút Start / Stop / Dump
        try {
            showControlNotification()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "showControlNotification failed", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Không xử lý event — chỉ đọc khi được hỏi
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(notifReceiver) } catch (_: Exception) {}
        getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        DebugLogger.w(TAG, "AccessibilityService destroyed")
        super.onDestroy()
    }

    // ── Persistent control notification (Start / Stop / Dump) ───

    private fun showControlNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        // Tạo channel (idempotent)
        val ch = NotificationChannel(CHANNEL, "Solver Controls", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Nút Start / Stop / Dump điều khiển nhanh từ thanh thông báo"
        nm.createNotificationChannel(ch)

        fun makePi(action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                this, code,
                Intent(action).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        val piStart   = makePi(ACTION_START_SOLVER,   1)
        val piStop    = makePi(ACTION_STOP_SOLVER,    2)
        val piToggle  = makePi(ACTION_TOGGLE_OVERLAY, 3)

        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("MultiCaptcha Solver ✓")
            .setContentText("Start/Stop solver • Toggle overlay (off = OCR sạch)")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .addAction(Notification.Action.Builder(null, "▶ Start",   piStart).build())
            .addAction(Notification.Action.Builder(null, "■ Stop",    piStop).build())
            .addAction(Notification.Action.Builder(null, "👁 Overlay", piToggle).build())
            .setOngoing(true)
            .build()

        nm.notify(NOTIF_ID, notif)
        DebugLogger.i(TAG, "Control notification shown (Start/Stop/Overlay)")
    }
}
