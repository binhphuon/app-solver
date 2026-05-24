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

    fun resetDefaults() {
        startDetectTop = 60f; startDetectBot = 69f; startTapY = 66f
        arrowTapX = 90f;  arrowTapY = 71f
        submitDetectTop = 75f; submitDetectBot = 82f; submitTapY = 77f
        matchCropLeft = 25f; matchCropRight = 49f; matchCropTop = 45f; matchCropBot = 65f
        // captchaOther intentionally not reset — user must re-enter
    }

    fun toDebugString(): String = """
        startBtn detect: $startDetectTop%-$startDetectBot%  tapY: $startTapY%
        arrow tap: X=$arrowTapX%  Y=$arrowTapY%
        submit detect: $submitDetectTop%-$submitDetectBot%  tapY: $submitTapY%
        matchThis crop: X=$matchCropLeft%-$matchCropRight%  Y=$matchCropTop%-$matchCropBot%
    """.trimIndent()
}
