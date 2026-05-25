package com.multicaptcha.solver.solver

/**
 * Shared result type cho mọi solver.
 *  - index: 1-based option index API trả về
 */
data class TaskResult(
    val index: Int,
    val success: Boolean,
)

/**
 * Interface chung cho mọi captcha solver (OMOcaptcha, tgsolve, …).
 * Cho phép swap provider runtime mà không phải sửa FuncaptchaSolver.
 */
interface CaptchaSolver {
    /** Submit image+question → return taskId hoặc null nếu fail */
    suspend fun createTask(
        imageBase64: String,
        question:    String = "",
        slotIdx:     Int = -1,
    ): String?

    /** Poll task → return TaskResult với index hoặc null nếu fail/timeout */
    suspend fun getTaskResult(
        taskId:     String,
        timeoutSec: Int = 30,
        slotIdx:    Int = -1,
    ): TaskResult?

    val providerName: String
}
