package com.multicaptcha.solver

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.multicaptcha.solver.core.DebugLogger
import com.multicaptcha.solver.core.RootShell
import com.multicaptcha.solver.core.WindowLayoutManager
import com.multicaptcha.solver.databinding.ActivityMainBinding
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Packages theo thứ tự slot: trái → phải
    private val TARGET_PACKAGES = arrayListOf(
        "a.baba",
        "a.dcdc",
        "a.fefe"
    )

    private val PREFS_NAME = "mcs_prefs"
    private val KEY_API    = "api_key"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SolverConfig.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.etApiKey.setText(prefs.getString(KEY_API, ""))
        binding.etCaptchaOther.setText(SolverConfig.captchaOther)

        // Check root + grant accessibility
        if (RootShell.hasRoot()) {
            binding.tvStatus.text = "✅ Root OK — đang bật Accessibility..."
            thread {
                RootShell.grantAccessibilityService(packageName)

                // Retry loop: đợi tối đa 10 giây cho service bind
                val deadline = System.currentTimeMillis() + 10_000L
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(500)
                    if (com.multicaptcha.solver.core.SolverAccessibilityService.isAvailable()) break
                }

                val status = RootShell.readAccessibilityStatus()
                runOnUiThread {
                    if (com.multicaptcha.solver.core.SolverAccessibilityService.isAvailable()) {
                        binding.tvStatus.text = "✅ Root + Accessibility OK"
                    } else {
                        binding.tvStatus.text = "⚠ Accessibility chưa bind\n$status"
                    }
                }
            }
        } else {
            binding.tvStatus.text = "⚠️ Chưa root!"
        }

        // ── Start ────────────────────────────────────────────────
        binding.btnStart.setOnClickListener {
            val apiKey = binding.etApiKey.text.toString().trim()
            if (apiKey.isBlank()) {
                Toast.makeText(this, "Nhập API key trước!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val captchaOther = binding.etCaptchaOther.text.toString().trim()
            if (captchaOther.isBlank()) {
                Toast.makeText(this, "⚠️ Chưa nhập captcha question text!", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_API, apiKey).apply()

            // Lưu captcha question text
            SolverConfig.captchaOther = binding.etCaptchaOther.text.toString().trim()

            startForegroundService(Intent(this, SolverService::class.java).apply {
                action = SolverService.ACTION_START
                putExtra(SolverService.EXTRA_API_KEY, apiKey)
                putStringArrayListExtra(SolverService.EXTRA_PACKAGES, TARGET_PACKAGES)
            })
            binding.tvStatus.text = "🟢 Solver đang chạy..."
            Toast.makeText(this, "Đã bắt đầu!", Toast.LENGTH_SHORT).show()
        }

        // ── Stop ─────────────────────────────────────────────────
        binding.btnStop.setOnClickListener {
            startService(Intent(this, SolverService::class.java).apply {
                action = SolverService.ACTION_STOP
            })
            binding.tvStatus.text = "🔴 Đã dừng"
            Toast.makeText(this, "Đã dừng!", Toast.LENGTH_SHORT).show()
        }

        // ── Dump accessibility tree ───────────────────────────────
        binding.btnDumpA11y.setOnClickListener {
            if (!com.multicaptcha.solver.core.SolverAccessibilityService.isAvailable()) {
                // Log debug info để diagnose
                thread {
                    val status = RootShell.readAccessibilityStatus()
                    runOnUiThread {
                        binding.tvStatus.text = "⚠ Accessibility chưa bind\n$status"
                        Toast.makeText(this,
                            "Accessibility chưa sẵn sàng!\n$status",
                            Toast.LENGTH_LONG).show()
                    }
                }
                return@setOnClickListener
            }
            com.multicaptcha.solver.core.DebugLogger.start()
            thread {
                com.multicaptcha.solver.core.SolverAccessibilityService.dumpAllWindows()
            }
            Toast.makeText(this, "Đã dump → xem solver_debug.txt", Toast.LENGTH_SHORT).show()
        }

        // ── Xóa log ──────────────────────────────────────────────
        binding.btnClearLog.setOnClickListener {
            File("/storage/emulated/0/Download/solver_debug.txt").delete()
            File("/storage/emulated/0/Download/solver_debug_old.txt").delete()
            Toast.makeText(this, "Đã xóa log", Toast.LENGTH_SHORT).show()
        }

        // ── Xếp windows ──────────────────────────────────────────
        binding.btnLayout.setOnClickListener {
            binding.btnLayout.isEnabled = false
            binding.tvPosInfo.text = "⏳ Đang xếp windows..."

            thread {
                val (screenW, screenH) = WindowLayoutManager.getScreenSize(this)
                val rects = WindowLayoutManager.buildRects(screenW, screenH, TARGET_PACKAGES.size)

                val sb = StringBuilder()
                sb.appendLine("Screen: ${screenW}x${screenH}")
                sb.appendLine()

                TARGET_PACKAGES.forEachIndexed { i, pkg ->
                    val rect   = rects[i]
                    val ok     = WindowLayoutManager.setWindowPosition(pkg, rect)
                    val status = if (ok) "✓" else "✗"
                    sb.appendLine("$status Slot[$i] $pkg")
                    sb.appendLine("  L=${rect.left} T=${rect.top} R=${rect.right} B=${rect.bottom}")
                }

                sb.appendLine()
                sb.appendLine("⚠ Cần tắt/bật lại từng floating window để áp dụng!")

                runOnUiThread {
                    binding.tvPosInfo.text = sb.toString()
                    binding.btnLayout.isEnabled = true
                    Toast.makeText(this, "Xong! Tắt/bật lại windows để áp dụng", Toast.LENGTH_LONG).show()
                }
            }
        }

        // ── Đọc vị trí hiện tại ──────────────────────────────────
        binding.btnReadPos.setOnClickListener {
            binding.btnReadPos.isEnabled = false
            binding.tvPosInfo.text = "⏳ Đang đọc..."

            thread {
                val sb = StringBuilder()
                TARGET_PACKAGES.forEachIndexed { i, pkg ->
                    val rect = WindowLayoutManager.readCurrentPosition(pkg)
                    sb.appendLine("Slot[$i] $pkg")
                    if (rect != null) {
                        sb.appendLine("  L=${rect.left} T=${rect.top} R=${rect.right} B=${rect.bottom}  (${rect.width}x${rect.height})")
                    } else {
                        sb.appendLine("  ✗ Không đọc được (chưa chạy lần nào?)")
                    }
                }

                runOnUiThread {
                    binding.tvPosInfo.text = sb.toString()
                    binding.btnReadPos.isEnabled = true
                }
            }
        }

        // ── Calibration ──────────────────────────────────────────
        loadCalibrationToUI()

        binding.btnSaveCalib.setOnClickListener {
            saveCalibrationFromUI()
            Toast.makeText(this, "✅ Đã lưu calibration", Toast.LENGTH_SHORT).show()
        }

        binding.btnResetCalib.setOnClickListener {
            SolverConfig.resetDefaults()
            loadCalibrationToUI()
            Toast.makeText(this, "🔁 Reset về mặc định", Toast.LENGTH_SHORT).show()
        }

        binding.btnTestTap.setOnClickListener {
            binding.btnTestTap.isEnabled = false
            thread {
                val (screenW, screenH) = WindowLayoutManager.getScreenSize(this)
                val slotWidth = screenW / TARGET_PACKAGES.size
                val slot = com.multicaptcha.solver.solver.SlotConfig(0, 0, slotWidth, screenH, TARGET_PACKAGES[0])

                // Tap Start position (green)
                com.multicaptcha.solver.core.TouchInjector.tapInSlot(slot, slot.startButtonRelX, slot.startButtonRelY, "TEST: Start")
                Thread.sleep(800)
                // Tap Arrow position (orange)
                com.multicaptcha.solver.core.TouchInjector.tapInSlot(slot, slot.rightArrowRelX, slot.rightArrowRelY, "TEST: Arrow→")
                Thread.sleep(800)
                // Tap Submit position (blue)
                com.multicaptcha.solver.core.TouchInjector.tapInSlot(slot, slot.submitRelX, slot.submitRelY, "TEST: Submit")

                runOnUiThread { binding.btnTestTap.isEnabled = true }
            }
        }
    }

    private fun loadCalibrationToUI() {
        // Hiển thị Float gọn: bỏ .0 nếu là số nguyên, giữ thập phân nếu có
        fun fmt(f: Float) = if (f == f.toLong().toFloat()) f.toLong().toString() else "%.1f".format(f)
        binding.etStartDetTop.setText(fmt(SolverConfig.startDetectTop))
        binding.etStartDetBot.setText(fmt(SolverConfig.startDetectBot))
        binding.etStartTapY.setText(fmt(SolverConfig.startTapY))
        binding.etArrowTapX.setText(fmt(SolverConfig.arrowTapX))
        binding.etArrowTapY.setText(fmt(SolverConfig.arrowTapY))
        binding.etSubDetTop.setText(fmt(SolverConfig.submitDetectTop))
        binding.etSubDetBot.setText(fmt(SolverConfig.submitDetectBot))
        binding.etSubTapY.setText(fmt(SolverConfig.submitTapY))
        binding.etMatchLeft.setText(fmt(SolverConfig.matchCropLeft))
        binding.etMatchRight.setText(fmt(SolverConfig.matchCropRight))
        binding.etMatchTop.setText(fmt(SolverConfig.matchCropTop))
        binding.etMatchBot.setText(fmt(SolverConfig.matchCropBot))
    }

    private fun saveCalibrationFromUI() {
        fun et(et: android.widget.EditText, default: Float) =
            et.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(0f, 100f) ?: default
        SolverConfig.startDetectTop  = et(binding.etStartDetTop,  60f)
        SolverConfig.startDetectBot  = et(binding.etStartDetBot,  69f)
        SolverConfig.startTapY       = et(binding.etStartTapY,    66f)
        SolverConfig.arrowTapX       = et(binding.etArrowTapX,    90f)
        SolverConfig.arrowTapY       = et(binding.etArrowTapY,    71f)
        SolverConfig.submitDetectTop = et(binding.etSubDetTop,    75f)
        SolverConfig.submitDetectBot = et(binding.etSubDetBot,    82f)
        SolverConfig.submitTapY      = et(binding.etSubTapY,      77f)
        SolverConfig.matchCropLeft   = et(binding.etMatchLeft,    25f)
        SolverConfig.matchCropRight  = et(binding.etMatchRight,   49f)
        SolverConfig.matchCropTop    = et(binding.etMatchTop,     45f)
        SolverConfig.matchCropBot    = et(binding.etMatchBot,     65f)
    }
}
