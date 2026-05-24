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
     * @param packageName  runtime package name (Context.packageName) — e.g. com.xxx.debug
     * @param serviceClassName  actual compiled class name (use ::class.java.name, NOT packageName+string)
     *
     * IMPORTANT: debug builds have applicationIdSuffix → packageName ≠ source package.
     * Always pass the real class name via reflection to avoid writing wrong entries.
     */
    fun grantAccessibilityService(packageName: String, serviceClassName: String) {
        val componentName = "$packageName/$serviceClassName"

        val current = exec("settings get secure enabled_accessibility_services").trim()
        Log.i(TAG, "Current accessibility services: '$current'")

        // Xoá mọi entry cũ của packageName này (bao gồm cả entry sai từ lần trước)
        val entries = if (current.isEmpty() || current == "null") emptyList()
                      else current.split(":").filter { it.isNotBlank() }
        val filtered = entries.filter { !it.startsWith("$packageName/") }

        val newList = (filtered + componentName).joinToString(":")

        // 2-step write để force ContentObserver fire ngay cả khi entry đã tồn tại từ trước:
        //   1. Ghi list KHÔNG có service của ta (briefly disconnect chỉ service mình, không đụng services khác như MacroDroid)
        //   2. Ghi list ĐẦY ĐỦ → AccessibilityManagerService rebind service ta
        val withoutOurs = if (filtered.isEmpty()) "\"\"" else filtered.joinToString(":")
        exec("settings put secure enabled_accessibility_services $withoutOurs")
        Thread.sleep(300)
        exec("settings put secure enabled_accessibility_services $newList")
        exec("settings put secure accessibility_enabled 1")
        Log.i(TAG, "Accessibility granted: $componentName")
    }

    /**
     * Đọc trạng thái accessibility hiện tại để debug
     */
    fun readAccessibilityStatus(): String {
        val services = exec("settings get secure enabled_accessibility_services").trim()
        val enabled  = exec("settings get secure accessibility_enabled").trim()
        return "enabled=$enabled\nservices=$services"
    }
}
