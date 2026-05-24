package com.multicaptcha.solver.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service để đọc UI của các floating window FunCaptcha.
 * Được grant tự động qua root shell khi app khởi động.
 *
 * Cung cấp:
 *  - getQuestionText(pkg): đọc câu hỏi từ FunCaptcha widget
 *  - getCounterText(pkg):  đọc "1 / 6" để biết tổng số options
 */
class SolverAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilitySvc"

        @Volatile private var instance: SolverAccessibilityService? = null

        fun isAvailable(): Boolean = instance != null

        /**
         * Dump toàn bộ window tree ra DebugLogger để kiểm tra xem accessibility
         * có đọc được nội dung floating windows không.
         */
        fun dumpAllWindows() {
            val svc = instance ?: run {
                DebugLogger.e(TAG, "dumpAllWindows: service not connected!")
                return
            }
            val windows = svc.windows ?: run {
                DebugLogger.e(TAG, "dumpAllWindows: windows = null")
                return
            }
            DebugLogger.sep("ACCESSIBILITY WINDOW DUMP (${windows.size} windows)")
            windows.forEachIndexed { wi, window ->
                val root = window.root
                val pkg  = root?.packageName ?: "null"
                DebugLogger.i(TAG, "Window[$wi] pkg=$pkg type=${window.type} " +
                    "layer=${window.layer} focused=${window.isFocused}")
                if (root != null) {
                    dumpNode(root, depth = 0, maxDepth = 6)
                    root.recycle()
                } else {
                    DebugLogger.w(TAG, "  root is null")
                }
            }
            DebugLogger.sep("END DUMP")
        }

        private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int) {
            if (depth > maxDepth) return
            val indent  = "  ".repeat(depth)
            val cls     = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val resId   = node.viewIdResourceName ?: ""
            val text    = node.text?.toString()?.take(60) ?: ""
            val desc    = node.contentDescription?.toString()?.take(40) ?: ""
            DebugLogger.d(TAG, "$indent[$cls] id=\"$resId\" text=\"$text\" desc=\"$desc\" " +
                "focusable=${node.isFocusable} children=${node.childCount}")
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                dumpNode(child, depth + 1, maxDepth)
                child.recycle()
            }
        }

        /**
         * Đọc câu hỏi của FunCaptcha từ UI.
         * Tìm node có text dài (>20 ký tự) trong cửa sổ thuộc [pkg].
         * Priority: node focusable trong FunCaptcha view → fallback bất kỳ text dài.
         */
        fun getQuestionText(pkg: String): String? {
            val svc = instance ?: return null
            return try {
                val windows = svc.windows ?: return null
                for (window in windows) {
                    val root = window.root ?: continue
                    val winPkg = root.packageName?.toString() ?: ""
                    if (!winPkg.contains(pkg, ignoreCase = true) &&
                        !pkg.contains(winPkg, ignoreCase = true)) {
                        root.recycle(); continue
                    }
                    // Tìm text dài trong FunCaptcha node
                    val text = findQuestionText(root)
                    root.recycle()
                    if (!text.isNullOrBlank()) {
                        DebugLogger.i(TAG, "getQuestionText[$pkg]: \"$text\"")
                        return text
                    }
                }
                DebugLogger.w(TAG, "getQuestionText[$pkg]: not found")
                null
            } catch (e: Exception) {
                DebugLogger.e(TAG, "getQuestionText exception", e)
                null
            }
        }

        /**
         * Đọc counter "1 / 6" để biết tổng số options trong carousel.
         * @return số nguyên (total) nếu đọc được, null nếu không tìm thấy.
         */
        fun getTotalOptions(pkg: String): Int? {
            val svc = instance ?: return null
            return try {
                val windows = svc.windows ?: return null
                for (window in windows) {
                    val root = window.root ?: continue
                    val winPkg = root.packageName?.toString() ?: ""
                    if (!winPkg.contains(pkg, ignoreCase = true) &&
                        !pkg.contains(winPkg, ignoreCase = true)) {
                        root.recycle(); continue
                    }
                    val total = findCounterTotal(root)
                    root.recycle()
                    if (total != null) {
                        DebugLogger.i(TAG, "getTotalOptions[$pkg]: $total")
                        return total
                    }
                }
                DebugLogger.d(TAG, "getTotalOptions[$pkg]: counter not found")
                null
            } catch (e: Exception) {
                DebugLogger.e(TAG, "getTotalOptions exception", e)
                null
            }
        }

        // ── Helpers ───────────────────────────────────────────────

        /**
         * DFS tìm node text dài (câu hỏi captcha).
         * Ưu tiên: focusable + text >20 chars; fallback: text >30 chars.
         */
        private fun findQuestionText(node: AccessibilityNodeInfo): String? {
            val text = node.text?.toString()?.trim() ?: ""
            if (text.length > 20 && node.isFocusable) return text

            var fallback: String? = null
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findQuestionText(child)
                child.recycle()
                if (found != null) {
                    if (found.length > 20 && node.isFocusable) return found  // exact match
                    if (fallback == null && found.length > 30) fallback = found
                }
            }
            // Check this node itself as fallback
            if (fallback == null && text.length > 30) fallback = text
            return fallback
        }

        /**
         * Tìm node có text dạng "N / M" hoặc "N/M" và trả về M (total).
         */
        private fun findCounterTotal(node: AccessibilityNodeInfo): Int? {
            val text = node.text?.toString()?.trim() ?: ""
            // Match "1 / 6", "1/6", "1 of 6", "1 / 20" ...
            val m = Regex("""(\d+)\s*[/of]+\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
            if (m != null) {
                val total = m.groupValues[2].toIntOrNull()
                if (total != null && total in 2..30) return total
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = findCounterTotal(child)
                child.recycle()
                if (found != null) return found
            }
            return null
        }
    }

    // ── Service lifecycle ────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
        // Đảm bảo service có quyền đọc tất cả windows
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = info
        DebugLogger.i(TAG, "AccessibilityService connected ✓")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Không xử lý event — chỉ đọc khi được hỏi
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        DebugLogger.w(TAG, "AccessibilityService destroyed")
        super.onDestroy()
    }
}
