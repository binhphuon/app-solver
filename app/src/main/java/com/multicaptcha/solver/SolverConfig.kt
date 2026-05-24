package com.multicaptcha.solver

import android.content.Context
import android.content.SharedPreferences

/**
 * Calibration values stored in SharedPreferences.
 * All values are Float representing % of slot height (or width for X).
 * Supports decimals like 65.5, 38.2, etc.
 * Call init() once from MainActivity.onCreate().
 */
object SolverConfig {

    private const val PREFS = "solver_calib"
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun getFloat(key: String, default: Float): Float = prefs?.getFloat(key, default) ?: default
    private fun setFloat(key: String, v: Float) { prefs?.edit()?.putFloat(key, v)?.apply() }

    // ── Start Puzzle button ──────────────────────────────────────
    var startDetectTop: Float
        get() = getFloat("sdt", 60f); set(v) = setFloat("sdt", v)
    var startDetectBot: Float
        get() = getFloat("sdb", 69f); set(v) = setFloat("sdb", v)
    var startTapY: Float
        get() = getFloat("sty", 66f); set(v) = setFloat("sty", v)

    // ── Right arrow (→) ─────────────────────────────────────────
    var arrowTapX: Float
        get() = getFloat("arx", 90f); set(v) = setFloat("arx", v)
    var arrowTapY: Float
        get() = getFloat("ary", 71f); set(v) = setFloat("ary", v)

    // ── Submit button ────────────────────────────────────────────
    var submitDetectTop: Float
        get() = getFloat("sudt", 75f); set(v) = setFloat("sudt", v)
    var submitDetectBot: Float
        get() = getFloat("sudb", 82f); set(v) = setFloat("sudb", v)
    var submitTapY: Float
        get() = getFloat("subty", 77f); set(v) = setFloat("subty", v)

    // ── Captcha question text (sent as "other" to OMOcaptcha API) ─
    var captchaOther: String
        get() = prefs?.getString("captcha_other", "") ?: ""
        set(v) { prefs?.edit()?.putString("captcha_other", v)?.apply() }

    // ── "Match This!" reference image crop ───────────────────────
    var matchCropLeft: Float
        get() = getFloat("mcl", 25f); set(v) = setFloat("mcl", v)
    var matchCropRight: Float
        get() = getFloat("mcr", 49f); set(v) = setFloat("mcr", v)
    var matchCropTop: Float
        get() = getFloat("mct", 45f); set(v) = setFloat("mct", v)
    var matchCropBot: Float
        get() = getFloat("mcb", 65f); set(v) = setFloat("mcb", v)

    // ── Vùng câu hỏi (OCR) — chỉnh được cả X và Y ────────────────
    var questionCropLeft: Float
        get() = getFloat("qcl", 2f);  set(v) = setFloat("qcl", v)
    var questionCropRight: Float
        get() = getFloat("qcr", 98f); set(v) = setFloat("qcr", v)
    var questionCropTop: Float
        get() = getFloat("qct", 30f); set(v) = setFloat("qct", v)
    var questionCropBot: Float
        get() = getFloat("qcb", 44f); set(v) = setFloat("qcb", v)

    // ── Vùng option hiện tại (carousel) — chỉnh được cả X và Y ───
    var optionCropLeft: Float
        get() = getFloat("ocl", 51f); set(v) = setFloat("ocl", v)
    var optionCropRight: Float
        get() = getFloat("ocr", 98f); set(v) = setFloat("ocr", v)
    var optionCropTop: Float
        get() = getFloat("oct", 45f); set(v) = setFloat("oct", v)
    var optionCropBot: Float
        get() = getFloat("ocb", 65f); set(v) = setFloat("ocb", v)

    // ── Vùng dots count (carousel page indicator phía trên Submit) — X+Y ─
    var dotsCropLeft: Float
        get() = getFloat("dcl", 10f); set(v) = setFloat("dcl", v)
    var dotsCropRight: Float
        get() = getFloat("dcr", 90f); set(v) = setFloat("dcr", v)
    var dotsCropTop: Float
        get() = getFloat("dct", 70f); set(v) = setFloat("dct", v)
    var dotsCropBot: Float
        get() = getFloat("dcb", 74f); set(v) = setFloat("dcb", v)

    fun resetDefaults() {
        startDetectTop = 60f; startDetectBot = 69f; startTapY = 66f
        arrowTapX = 90f;  arrowTapY = 71f
        submitDetectTop = 75f; submitDetectBot = 82f; submitTapY = 77f
        matchCropLeft = 25f; matchCropRight = 49f; matchCropTop = 45f; matchCropBot = 65f
        questionCropLeft = 2f; questionCropRight = 98f; questionCropTop = 30f; questionCropBot = 44f
        optionCropLeft = 51f; optionCropRight = 98f; optionCropTop = 45f; optionCropBot = 65f
        dotsCropLeft = 10f; dotsCropRight = 90f; dotsCropTop = 70f; dotsCropBot = 74f
        // captchaOther intentionally not reset — user must re-enter
    }

    fun toDebugString(): String = """
        startBtn detect: $startDetectTop%-$startDetectBot%  tapY: $startTapY%
        arrow tap: X=$arrowTapX%  Y=$arrowTapY%
        submit detect: $submitDetectTop%-$submitDetectBot%  tapY: $submitTapY%
        matchThis crop: X=$matchCropLeft%-$matchCropRight%  Y=$matchCropTop%-$matchCropBot%
    """.trimIndent()
}
