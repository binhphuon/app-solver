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
     */
    fun grantAccessibilityService(packageName: String) {
        val componentName = "$packageName/.core.SolverAccessibilityService"
        // Đọc danh sách service đang enabled
        val current = exec("settings get secure enabled_accessibility_services").trim()
        if (current.contains(componentName)) {
            Log.i(TAG, "Accessibility already granted: $componentName")
            return
        }
        val newList = if (current.isEmpty() || current == "null") componentName
                      else "$current:$componentName"
        exec("settings put secure enabled_accessibility_services $newList")
        exec("settings put secure accessibility_enabled 1")
        Log.i(TAG, "Accessibility granted: $componentName")
    }
}
