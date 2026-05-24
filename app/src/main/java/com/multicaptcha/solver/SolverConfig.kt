package com.multicaptcha.solver

import android.content.Context
import android.content.SharedPreferences

/**
 * Calibration values stored in SharedPreferences.
 * All values are integers representing % of slot height (or width for X).
 * Call init() once from MainActivity.onCreate().
 *
 * Defaults = giá trị tốt nhất đã calibrate thực tế:
 *   startBtn detect: 60-69  tap Y: 66
 *   arrow tap: X=90  Y=71
 *   submit detect: 75-82  tap Y: 77
 *   matchThis crop: X=25-49  Y=45-65
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
        get() = getInt("sdt", 60); set(v) = setInt("sdt", v)
    var startDetectBot: Int
        get() = getInt("sdb", 69); set(v) = setInt("sdb", v)
    var startTapY: Int                          // % of slot height
        get() = getInt("sty", 66); set(v) = setInt("sty", v)

    // ── Right arrow (→) ─────────────────────────────────────────
    var arrowTapX: Int                          // % of slot width
        get() = getInt("arx", 90); set(v) = setInt("arx", v)
    var arrowTapY: Int
        get() = getInt("ary", 71); set(v) = setInt("ary", v)

    // ── Submit button ────────────────────────────────────────────
    var submitDetectTop: Int
        get() = getInt("sudt", 75); set(v) = setInt("sudt", v)
    var submitDetectBot: Int
        get() = getInt("sudb", 82); set(v) = setInt("sudb", v)
    var submitTapY: Int
        get() = getInt("subty", 77); set(v) = setInt("subty", v)

    // ── "Match This!" reference image crop ───────────────────────
    var matchCropLeft: Int                        // % of slot width
        get() = getInt("mcl", 25); set(v) = setInt("mcl", v)
    var matchCropRight: Int
        get() = getInt("mcr", 49); set(v) = setInt("mcr", v)
    var matchCropTop: Int                         // % of slot height
        get() = getInt("mct", 45); set(v) = setInt("mct", v)
    var matchCropBot: Int
        get() = getInt("mcb", 65); set(v) = setInt("mcb", v)

    fun resetDefaults() {
        startDetectTop = 60; startDetectBot = 69; startTapY = 66
        arrowTapX = 90;  arrowTapY = 71
        submitDetectTop = 75; submitDetectBot = 82; submitTapY = 77
        matchCropLeft = 25; matchCropRight = 49; matchCropTop = 45; matchCropBot = 65
    }

    fun toDebugString(): String = """
        startBtn detect: $startDetectTop%-$startDetectBot%  tapY: $startTapY%
        arrow tap: X=$arrowTapX%  Y=$arrowTapY%
        submit detect: $submitDetectTop%-$submitDetectBot%  tapY: $submitTapY%
        matchThis crop: X=$matchCropLeft%-$matchCropRight%  Y=$matchCropTop%-$matchCropBot%
    """.trimIndent()
}
