package com.multicaptcha.solver

import android.content.Context
import android.content.SharedPreferences

/**
 * Calibration values stored in SharedPreferences.
 * All values are integers representing % of slot height (or width for X).
 * Call init() once from MainActivity.onCreate().
 */
object SolverConfig {

    private const val PREFS = "solver_calib"
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun getInt(key: String, default: Int): Int = prefs?.getInt(key, default) ?: default
    private fun setInt(key: String, v: Int) { prefs?.edit()?.putInt(key, v)?.apply() }

    // ── Start Puzzle button ──────────────────────────────────────
    var startDetectTop: Int
        get() = getInt("sdt", 52); set(v) = setInt("sdt", v)
    var startDetectBot: Int
        get() = getInt("sdb", 82); set(v) = setInt("sdb", v)
    var startTapY: Int                          // % of slot height
        get() = getInt("sty", 70); set(v) = setInt("sty", v)

    // ── Right arrow (→) ─────────────────────────────────────────
    var arrowTapX: Int                          // % of slot width
        get() = getInt("arx", 68); set(v) = setInt("arx", v)
    var arrowTapY: Int
        get() = getInt("ary", 73); set(v) = setInt("ary", v)

    // ── Submit button ────────────────────────────────────────────
    var submitDetectTop: Int
        get() = getInt("sudt", 75); set(v) = setInt("sudt", v)
    var submitDetectBot: Int
        get() = getInt("sudb", 92); set(v) = setInt("sudb", v)
    var submitTapY: Int
        get() = getInt("subty", 80); set(v) = setInt("subty", v)

    // ── "Match This!" reference image crop ───────────────────────
    var matchCropTop: Int
        get() = getInt("mct", 38); set(v) = setInt("mct", v)
    var matchCropBot: Int
        get() = getInt("mcb", 68); set(v) = setInt("mcb", v)

    fun resetDefaults() {
        startDetectTop = 52; startDetectBot = 82; startTapY = 70
        arrowTapX = 68;  arrowTapY = 73
        submitDetectTop = 75; submitDetectBot = 92; submitTapY = 80
        matchCropTop = 38; matchCropBot = 68
    }

    fun toDebugString(): String = """
        startBtn detect: $startDetectTop%-$startDetectBot%  tapY: $startTapY%
        arrow tap: X=$arrowTapX%  Y=$arrowTapY%
        submit detect: $submitDetectTop%-$submitDetectBot%  tapY: $submitTapY%
        matchThis crop: $matchCropTop%-$matchCropBot%
    """.trimIndent()
}
