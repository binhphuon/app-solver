package com.multicaptcha.solver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.multicaptcha.solver.core.DebugLogger
import com.multicaptcha.solver.core.RootShell
import com.multicaptcha.solver.core.WindowLayoutManager
import com.multicaptcha.solver.databinding.ActivityMainBinding
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Packages theo thứ tự slot: trái → phải (dùng chung với notification Start)
    private val TARGET_PACKAGES get() = SolverService.DEFAULT_PACKAGES

    private val PREFS_NAME      = "mcs_prefs"
    private val KEY_API_OMO     = "api_key_omo"
    private val KEY_API_TGSOLVE = "api_key_tgsolve"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SolverConfig.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Android 13+ cần runtime permission để hiện notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Load provider radio + key của provider đó
        fun keyNameFor(provider: String) = if (provider == "tgsolve") KEY_API_TGSOLVE else KEY_API_OMO
        val savedProvider = SolverConfig.solverProvider
        if (savedProvider == "tgsolve") binding.rbTgsolve.isChecked = true
        else binding.rbOmo.isChecked = true
        binding.etApiKey.setText(prefs.getString(keyNameFor(savedProvider), ""))

        // Khi đổi radio: save key hiện tại vào provider cũ, load key của provider mới
        binding.rgProvider.setOnCheckedChangeListener { _, checkedId ->
            // Save key hiện tại vào provider đang select (trước khi đổi)
            val current = binding.etApiKey.text.toString().trim()
            val oldProvider = SolverConfig.solverProvider
            if (current.isNotEmpty()) {
                prefs.edit().putString(keyNameFor(oldProvider), current).apply()
            }
            // Switch provider
            val newProvider = if (checkedId == R.id.rbTgsolve) "tgsolve" else "omo"
            SolverConfig.solverProvider = newProvider
            binding.etApiKey.setText(prefs.getString(keyNameFor(newProvider), ""))
        }

        // Check root + grant accessibility
        if (RootShell.hasRoot()) {
            binding.tvStatus.text = "✅ Root OK — đang bật Accessibility..."
            thread {
                // Dùng ::class.java.name để lấy tên class thực (không bị ảnh hưởng bởi applicationIdSuffix)
                RootShell.grantAccessibilityService(
                    packageName,
                    com.multicaptcha.solver.core.SolverAccessibilityService::class.java.name
                )

                // Retry loop: đợi tối đa 20 giây cho service bind
                // (sau khi toggle settings, AMS cần vài giây để re-evaluate)
                val deadline = System.currentTimeMillis() + 20_000L
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
            val provider = SolverConfig.solverProvider
            // Save key dưới name của provider đang chọn
            prefs.edit().putString(keyNameFor(provider), apiKey).apply()

            startForegroundService(Intent(this, SolverService::class.java).apply {
                action = SolverService.ACTION_START
                putExtra(SolverService.EXTRA_API_KEY, apiKey)
                putExtra(SolverService.EXTRA_PROVIDER, provider)
                putStringArrayListExtra(SolverService.EXTRA_PACKAGES, TARGET_PACKAGES)
            })
            binding.tvStatus.text = "🟢 Solver đang chạy ($provider)..."
            Toast.makeText(this, "Đã bắt đầu ($provider)!", Toast.LENGTH_SHORT).show()
        }

        // ── Stop ─────────────────────────────────────────────────
        binding.btnStop.setOnClickListener {
            startService(Intent(this, SolverService::class.java).apply {
                action = SolverService.ACTION_STOP
            })
            binding.tvStatus.text = "🔴 Đã dừng"
            Toast.makeText(this, "Đã dừng!", Toast.LENGTH_SHORT).show()
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
        binding.etTryDetTop.setText(fmt(SolverConfig.tryAgainDetectTop))
        binding.etTryDetBot.setText(fmt(SolverConfig.tryAgainDetectBot))
        binding.etTryTapY.setText(fmt(SolverConfig.tryAgainTapY))
        binding.etMatchLeft.setText(fmt(SolverConfig.matchCropLeft))
        binding.etMatchRight.setText(fmt(SolverConfig.matchCropRight))
        binding.etMatchTop.setText(fmt(SolverConfig.matchCropTop))
        binding.etMatchBot.setText(fmt(SolverConfig.matchCropBot))
        binding.etOptLeft.setText(fmt(SolverConfig.optionCropLeft))
        binding.etOptRight.setText(fmt(SolverConfig.optionCropRight))
        binding.etOptTop.setText(fmt(SolverConfig.optionCropTop))
        binding.etOptBot.setText(fmt(SolverConfig.optionCropBot))
        binding.etQLeft.setText(fmt(SolverConfig.questionCropLeft))
        binding.etQRight.setText(fmt(SolverConfig.questionCropRight))
        binding.etQTop.setText(fmt(SolverConfig.questionCropTop))
        binding.etQBot.setText(fmt(SolverConfig.questionCropBot))
        binding.etDotsLeft.setText(fmt(SolverConfig.dotsCropLeft))
        binding.etDotsRight.setText(fmt(SolverConfig.dotsCropRight))
        binding.etDotsTop.setText(fmt(SolverConfig.dotsCropTop))
        binding.etDotsBot.setText(fmt(SolverConfig.dotsCropBot))
    }

    private fun saveCalibrationFromUI() {
        fun et(et: android.widget.EditText, default: Float) =
            et.text.toString().replace(',', '.').toFloatOrNull()?.coerceIn(0f, 100f) ?: default
        SolverConfig.startDetectTop  = et(binding.etStartDetTop,  60f)
        SolverConfig.startDetectBot  = et(binding.etStartDetBot,  69f)
        SolverConfig.startTapY       = et(binding.etStartTapY,    66f)
        SolverConfig.arrowTapX       = et(binding.etArrowTapX,    89.3f)
        SolverConfig.arrowTapY       = et(binding.etArrowTapY,    71f)
        SolverConfig.submitDetectTop = et(binding.etSubDetTop,    76.2f)
        SolverConfig.submitDetectBot = et(binding.etSubDetBot,    79f)
        SolverConfig.submitTapY      = et(binding.etSubTapY,      77.5f)
        SolverConfig.tryAgainDetectTop = et(binding.etTryDetTop,  69f)
        SolverConfig.tryAgainDetectBot = et(binding.etTryDetBot,  72f)
        SolverConfig.tryAgainTapY      = et(binding.etTryTapY,    70.5f)
        SolverConfig.matchCropLeft   = et(binding.etMatchLeft,    25.5f)
        SolverConfig.matchCropRight  = et(binding.etMatchRight,   48.5f)
        SolverConfig.matchCropTop    = et(binding.etMatchTop,     46f)
        SolverConfig.matchCropBot    = et(binding.etMatchBot,     65f)
        SolverConfig.optionCropLeft  = et(binding.etOptLeft,      48.5f)
        SolverConfig.optionCropRight = et(binding.etOptRight,     80f)
        SolverConfig.optionCropTop   = et(binding.etOptTop,       46f)
        SolverConfig.optionCropBot   = et(binding.etOptBot,       65f)
        SolverConfig.questionCropLeft  = et(binding.etQLeft,      24f)
        SolverConfig.questionCropRight = et(binding.etQRight,     93f)
        SolverConfig.questionCropTop   = et(binding.etQTop,       40f)
        SolverConfig.questionCropBot   = et(binding.etQBot,       44.7f)
        SolverConfig.dotsCropLeft    = et(binding.etDotsLeft,     40f)
        SolverConfig.dotsCropRight   = et(binding.etDotsRight,    95f)
        SolverConfig.dotsCropTop     = et(binding.etDotsTop,      73.6f)
        SolverConfig.dotsCropBot     = et(binding.etDotsBot,      75.5f)
    }
}
