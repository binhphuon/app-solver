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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        binding.etApiKey.setText(prefs.getString(KEY_API, ""))

        // Check root
        binding.tvStatus.text = if (RootShell.hasRoot()) "✅ Root OK" else "⚠️ Chưa root!"

        // ── Start ────────────────────────────────────────────────
        binding.btnStart.setOnClickListener {
            val apiKey = binding.etApiKey.text.toString().trim()
            if (apiKey.isBlank()) {
                Toast.makeText(this, "Nhập API key trước!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(KEY_API, apiKey).apply()

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
    }
}
