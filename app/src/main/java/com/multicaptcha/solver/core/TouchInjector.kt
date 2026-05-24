package com.multicaptcha.solver.core

import com.multicaptcha.solver.solver.SlotConfig

object TouchInjector {

    private const val TAG = "TouchInjector"

    fun tap(x: Int, y: Int, label: String = "") {
        DebugLogger.d(TAG, "input tap $x $y${if (label.isNotEmpty()) " ← $label" else ""}")
        RootShell.execSilent("input tap $x $y")
        OverlayManager.showTapFlash(x, y)
    }

    fun tapInSlot(slot: SlotConfig, relX: Float, relY: Float, label: String = "") {
        val absX = slot.xOffset + (slot.width  * relX).toInt()
        val absY =                (slot.height * relY).toInt()
        DebugLogger.tap(slot.index, absX, absY, label.ifEmpty { "rel(${fmtF(relX)}, ${fmtF(relY)})" })
        RootShell.execSilent("input tap $absX $absY")
        OverlayManager.showTapFlash(absX, absY)
    }

    fun tapInSlotPx(slot: SlotConfig, localX: Int, localY: Int, label: String = "") {
        val absX = slot.xOffset + localX
        DebugLogger.tap(slot.index, absX, localY, label)
        RootShell.execSilent("input tap $absX $localY")
        OverlayManager.showTapFlash(absX, localY)
    }

    private fun fmtF(f: Float) = "%.2f".format(f)
}
