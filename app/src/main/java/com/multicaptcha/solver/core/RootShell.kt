package com.multicaptcha.solver.core

import android.util.Log

object RootShell {

    private const val TAG = "RootShell"

    /**
     * Chạy lệnh shell với quyền root, trả về stdout
     */
    fun exec(cmd: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = process.inputStream.bufferedReader().readText()
            val error  = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (error.isNotEmpty()) Log.w(TAG, "stderr: $error")
            output.trim()
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $cmd", e)
            ""
        }
    }

    /**
     * Chạy lệnh không cần output (fire & forget)
     */
    fun execSilent(cmd: String) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        } catch (e: Exception) {
            Log.e(TAG, "execSilent failed: $cmd", e)
        }
    }

    /**
     * Kiểm tra máy có root không
     */
    fun hasRoot(): Boolean {
        val result = exec("id")
        return result.contains("uid=0")
    }

    /**
     * Grant Accessibility Service cho app qua root.
     * Không cần user vào Settings → Accessibility.
     * Dùng full class name (không dùng dot-notation) để tương thích mọi ROM.
     * Sau khi ghi setting, gửi broadcast để AccessibilityManagerService re-evaluate ngay.
     */
    fun grantAccessibilityService(packageName: String) {
        // Full class name — dot-notation (.core.Xxx) một số ROM không nhận
        val fullClass     = "$packageName.core.SolverAccessibilityService"
        val componentName = "$packageName/$fullClass"

        val current = exec("settings get secure enabled_accessibility_services").trim()
        Log.i(TAG, "Current accessibility services: '$current'")

        if (current.contains(componentName)) {
            Log.i(TAG, "Accessibility already granted: $componentName")
            // Vẫn đảm bảo accessibility_enabled = 1
            exec("settings put secure accessibility_enabled 1")
            return
        }

        val newList = when {
            current.isEmpty() || current == "null" -> componentName
            else -> "$current:$componentName"
        }
        exec("settings put secure enabled_accessibility_services $newList")
        exec("settings put secure accessibility_enabled 1")
        Log.i(TAG, "Accessibility granted: $componentName (newList=$newList)")

        // Trigger AccessibilityManagerService re-evaluate setting ngay lập tức
        exec("am broadcast -a android.intent.action.BOOT_COMPLETED -p $packageName --user 0 > /dev/null 2>&1 || true")
    }

    /**
     * Đọc trạng thái accessibility hiện tại để debug
     */
    fun readAccessibilityStatus(): String {
        val services = exec("settings get secure enabled_accessibility_services").trim()
        val enabled  = exec("settings get secure accessibility_enabled").trim()
        return "enabled=$enabled services=$services"
    }
}
