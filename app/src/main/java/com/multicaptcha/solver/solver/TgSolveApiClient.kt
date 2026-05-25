package com.multicaptcha.solver.solver

import com.multicaptcha.solver.core.DebugLogger
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * tgsolve.com API client. Format khác OMO:
 *  - POST /api/v1/in.php?key=...   form-encoded body (type-captcha, method, body, imginstructions)
 *  - Response plain text: "OK|TASK_ID" hoặc "ERROR_..."
 *  - GET  /api/v1/res.php?key=...&action=get&id=...
 *  - Response: "CAPCHA_NOT_READY", "OK|<index>", hoặc "ERROR_..."
 */
class TgSolveApiClient(private val apiKey: String) : CaptchaSolver {

    override val providerName: String = "tgsolve"

    private val TAG = "TgSolveApi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val SUBMIT_URL = "https://api.tgsolve.com/api/v1/in.php"
    private val RESULT_URL = "https://api.tgsolve.com/api/v1/res.php"

    override suspend fun createTask(imageBase64: String, question: String, slotIdx: Int): String? {
        DebugLogger.apiCreateTask(slotIdx, imageBase64.length * 3 / 4)
        DebugLogger.i(TAG, "════ POST $SUBMIT_URL  imginstructions=\"$question\"")

        val maskedKey = if (apiKey.length > 12) "${apiKey.take(8)}...${apiKey.takeLast(4)}" else "<short>"
        DebugLogger.i(TAG, "════ key=$maskedKey  body_b64=${imageBase64.length} chars")

        val form = FormBody.Builder()
            .add("type-captcha",     "funcaptcha-img")
            .add("method",           "base64")
            .add("body",             imageBase64)
            .add("imginstructions",  question)
            .build()

        return try {
            val req = Request.Builder()
                .url("$SUBMIT_URL?key=$apiKey")
                .post(form)
                .build()
            val resp = client.newCall(req).execute()
            val raw  = resp.body?.string()?.trim() ?: ""
            DebugLogger.d(TAG, "createTask response HTTP${resp.code}: $raw")

            if (raw.startsWith("OK|")) {
                val tid = raw.removePrefix("OK|").trim()
                DebugLogger.apiTaskCreated(slotIdx, tid)
                tid
            } else {
                DebugLogger.apiError(slotIdx, "submit error: $raw")
                null
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "createTask exception slot=$slotIdx", e)
            null
        }
    }

    override suspend fun getTaskResult(taskId: String, timeoutSec: Int, slotIdx: Int): TaskResult? {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        var attempt = 0

        while (System.currentTimeMillis() < deadline) {
            delay(1500)
            attempt++

            try {
                val req = Request.Builder()
                    .url("$RESULT_URL?key=$apiKey&action=get&id=$taskId")
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val raw  = resp.body?.string()?.trim() ?: ""

                DebugLogger.apiPollResult(slotIdx, attempt, raw)
                DebugLogger.d(TAG, "poll#$attempt raw: $raw")

                val upper = raw.uppercase()
                if ("NOT_READY" in upper || "PROCESSING" in upper) continue

                if (raw.startsWith("OK|")) {
                    val idxStr = raw.removePrefix("OK|").trim()
                    val index = idxStr.toIntOrNull()
                    if (index == null || index < 0) {
                        DebugLogger.apiError(slotIdx, "OK but bad index: '$idxStr'")
                        return null
                    }
                    DebugLogger.apiResult(slotIdx, index, (index - 1).coerceAtLeast(0))
                    return TaskResult(index = index, success = true)
                }
                if (upper.startsWith("ERROR")) {
                    DebugLogger.apiError(slotIdx, "task failed: $raw")
                    return null
                }
                // Plain index (some endpoints return just number)
                raw.toIntOrNull()?.let { idx ->
                    if (idx > 0) {
                        DebugLogger.apiResult(slotIdx, idx, idx - 1)
                        return TaskResult(index = idx, success = true)
                    }
                }
                DebugLogger.w(TAG, "unrecognized response: $raw")
            } catch (e: Exception) {
                DebugLogger.e(TAG, "poll#$attempt exception", e)
                delay(1000)
            }
        }
        DebugLogger.apiError(slotIdx, "timeout after ${timeoutSec}s ($attempt polls)")
        return null
    }
}
