package com.multicaptcha.solver.solver

import android.util.Base64
import com.multicaptcha.solver.core.DebugLogger
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class TaskResult(
    val index: Int,
    val success: Boolean
)

class OmoApiClient(private val apiKey: String) {

    private val TAG  = "OmoApiClient"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val CREATE_URL = "https://api.omocaptcha.com/v2/createTask"
    private val RESULT_URL = "https://api.omocaptcha.com/v2/getTaskResult"

    // ── Create task ──────────────────────────────────────────────

    suspend fun createTask(imageBase64: String, question: String = "", slotIdx: Int = -1): String? {
        DebugLogger.apiCreateTask(slotIdx, imageBase64.length * 3 / 4)

        val otherText = question.ifEmpty { com.multicaptcha.solver.SolverConfig.captchaOther }
        DebugLogger.d(TAG, "createTask other=\"$otherText\"")

        if (otherText.isBlank()) {
            DebugLogger.e(TAG, "slot=$slotIdx — 'other' field is empty! Set captcha question in UI before starting.")
        }

        val body = JSONObject().apply {
            put("clientKey", apiKey)
            put("task", JSONObject().apply {
                put("type", "FuncaptchaImageTask")
                put("imageBase64", imageBase64)
                put("other", otherText)          // required field — luôn gửi
            })
        }.toString()

        return try {
            DebugLogger.d(TAG, "POST $CREATE_URL")
            val req = Request.Builder()
                .url(CREATE_URL)
                .post(body.toRequestBody(JSON))
                .build()

            val resp = client.newCall(req).execute()
            val raw  = resp.body?.string() ?: ""
            DebugLogger.d(TAG, "createTask response HTTP${resp.code}: $raw")

            val json = JSONObject(raw)
            val errorId = json.optInt("errorId", 1)

            if (errorId != 0) {
                val desc = json.optString("errorDescription", "unknown")
                DebugLogger.apiError(slotIdx, "errorId=$errorId desc=$desc")
                return null
            }

            val taskId = json.optString("taskId", "")
            if (taskId.isEmpty()) {
                DebugLogger.apiError(slotIdx, "taskId empty in response")
                return null
            }

            DebugLogger.apiTaskCreated(slotIdx, taskId)
            taskId

        } catch (e: Exception) {
            DebugLogger.e(TAG, "createTask exception slot=$slotIdx", e)
            null
        }
    }

    // ── Poll result ──────────────────────────────────────────────

    suspend fun getTaskResult(taskId: String, timeoutSec: Int = 30, slotIdx: Int = -1): TaskResult? {
        val body = JSONObject().apply {
            put("clientKey", apiKey)
            put("taskId", taskId)
        }.toString()

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        var attempt  = 0

        while (System.currentTimeMillis() < deadline) {
            delay(1200)
            attempt++

            try {
                val req = Request.Builder()
                    .url(RESULT_URL)
                    .post(body.toRequestBody(JSON))
                    .build()

                val resp = client.newCall(req).execute()
                val raw  = resp.body?.string() ?: ""
                val json = JSONObject(raw)
                val status = json.optString("status", "")

                DebugLogger.apiPollResult(slotIdx, attempt, status)
                DebugLogger.d(TAG, "poll#$attempt raw: $raw")

                when (status) {
                    "ready" -> {
                        val solution = json.optJSONObject("solution")
                        val index    = solution?.optInt("index", -1) ?: -1

                        if (index < 0) {
                            DebugLogger.apiError(slotIdx, "ready but index=$index — raw=$raw")
                            return null
                        }

                        val clickCount = (index - 1).coerceAtLeast(0)
                        DebugLogger.apiResult(slotIdx, index, clickCount)
                        return TaskResult(index = index, success = true)
                    }
                    "fail" -> {
                        val desc = json.optString("errorDescription", "no description")
                        DebugLogger.apiError(slotIdx, "task failed: $desc")
                        return null
                    }
                    else -> {
                        // processing / pending — tiếp tục poll
                    }
                }

            } catch (e: Exception) {
                DebugLogger.e(TAG, "poll#$attempt exception", e)
                delay(1000)
            }
        }

        DebugLogger.apiError(slotIdx, "timeout after ${timeoutSec}s ($attempt polls)")
        return null
    }
}
