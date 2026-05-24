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
}
