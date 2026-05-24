package com.multicaptcha.solver.core

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Tự động xếp các floating window của App Cloner vào đúng vị trí
 * để captcha hiển thị đầy đủ, không che nhau.
 *
 * App Cloner lưu vị trí window tại:
 * /data/data/{package}/shared_prefs/{package}_preferences.xml
 *
 * Các key cần sửa:
 *   app_cloner_current_window_left
 *   app_cloner_current_window_top
 *   app_cloner_current_window_right
 *   app_cloner_current_window_bottom
 */
object WindowLayoutManager {

    private const val TAG     = "WindowLayout"
    private const val TMP_DIR = "/data/local/tmp/mcs_xml"

    data class WindowRect(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width  get() = right - left
        val height get() = bottom - top
        override fun toString() = "[$left,$top - $right,$bottom] (${width}x$height)"
    }

    // ── Public API ───────────────────────────────────────────────

    /**
     * Chia màn hình thành N cột đều nhau (landscape) và đặt từng package vào đúng slot.
     * Gọi trước khi start solver để đảm bảo layout chuẩn.
     *
     * @param packages  List package name theo thứ tự trái → phải
     * @param context   Dùng để lấy screen size
     */
    fun layoutWindows(packages: List<String>, context: Context) {
        val (screenW, screenH) = getScreenSize(context)
        DebugLogger.i(TAG, "layoutWindows: screen=${screenW}x${screenH}, slots=${packages.size}")

        val rects = buildRects(screenW, screenH, packages.size)

        packages.forEachIndexed { index, pkg ->
            val rect = rects[index]
            DebugLogger.i(TAG, "  Slot[$index] pkg=$pkg → $rect")
            setWindowPosition(pkg, rect)
        }

        DebugLogger.i(TAG, "layoutWindows done — restart floating windows to apply")
    }

    /**
     * Tính danh sách WindowRect cho N slot chia đều màn hình ngang
     */
    fun buildRects(screenW: Int, screenH: Int, count: Int): List<WindowRect> {
        val colW = screenW / count
        return (0 until count).map { i ->
            WindowRect(
                left   = i * colW,
                top    = 0,
                right  = i * colW + colW,
                bottom = screenH
            )
        }
    }

    // ── Core: Đọc và ghi XML qua root ───────────────────────────

    /**
     * Sửa vị trí window của 1 package trong shared_prefs XML
     */
    fun setWindowPosition(packageName: String, rect: WindowRect): Boolean {
        val xmlPath = "/data/data/$packageName/shared_prefs/${packageName}_preferences.xml"
        val tmpPath = "$TMP_DIR/${packageName.replace('.', '_')}_prefs.xml"

        // 1. Kiểm tra file tồn tại
        val exists = RootShell.exec("[ -f '$xmlPath' ] && echo yes || echo no").trim()
        if (exists != "yes") {
            DebugLogger.w(TAG, "Prefs XML not found: $xmlPath")
            return false
        }

        // 2. Copy file về tmp để xử lý
        RootShell.exec("mkdir -p $TMP_DIR")
        RootShell.exec("cp '$xmlPath' '$tmpPath' && chmod 666 '$tmpPath'")

        val tmpFile = File(tmpPath)
        if (!tmpFile.exists()) {
            DebugLogger.e(TAG, "Failed to copy XML to $tmpPath")
            return false
        }

        // 3. Parse và sửa XML
        val success = modifyPrefsXml(tmpFile, rect)
        if (!success) {
            DebugLogger.e(TAG, "modifyPrefsXml failed for $packageName")
            return false
        }

        // 4. Copy lại với đúng owner và permission
        RootShell.exec("cp '$tmpPath' '$xmlPath'")
        // Restore SELinux context + owner (App Cloner thường chạy với uid của package)
        val uid = RootShell.exec("stat -c %u '$xmlPath'").trim()
        if (uid.isNotEmpty() && uid != "0") {
            RootShell.exec("chown $uid:$uid '$xmlPath'")
        }
        RootShell.exec("chmod 660 '$xmlPath'")
        RootShell.exec("restorecon '$xmlPath' 2>/dev/null || true")

        DebugLogger.i(TAG, "✓ Updated $packageName → $rect")

        // 5. Dọn tmp
        RootShell.execSilent("rm -f '$tmpPath'")
        return true
    }

    /**
     * Parse SharedPreferences XML và update 4 key vị trí window
     *
     * Format XML của Android SharedPreferences:
     * <map>
     *   <int name="app_cloner_current_window_left" value="0" />
     *   ...
     * </map>
     */
    private fun modifyPrefsXml(file: File, rect: WindowRect): Boolean {
        return try {
            val dbf  = DocumentBuilderFactory.newInstance()
            val doc  = dbf.newDocumentBuilder().parse(file)
            val root = doc.documentElement

            val updates = mapOf(
                "app_cloner_current_window_left"   to rect.left,
                "app_cloner_current_window_top"    to rect.top,
                "app_cloner_current_window_right"  to rect.right,
                "app_cloner_current_window_bottom" to rect.bottom
            )

            val nodes    = root.childNodes
            val found    = mutableSetOf<String>()
            val notFound = mutableSetOf<String>()

            // Sửa các node đã tồn tại
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node !is Element) continue
                val name = node.getAttribute("name")
                if (updates.containsKey(name)) {
                    val newVal = updates[name].toString()
                    val oldVal = node.getAttribute("value")
                    node.setAttribute("value", newVal)
                    DebugLogger.d(TAG, "  XML[$name]: $oldVal → $newVal")
                    found.add(name)
                }
            }

            // Tạo node mới cho key chưa tồn tại
            updates.forEach { (name, value) ->
                if (!found.contains(name)) {
                    notFound.add(name)
                    val newNode = doc.createElement("int")
                    newNode.setAttribute("name", name)
                    newNode.setAttribute("value", value.toString())
                    root.appendChild(newNode)
                    DebugLogger.d(TAG, "  XML[$name]: created → $value")
                }
            }

            if (notFound.isNotEmpty()) {
                DebugLogger.w(TAG, "Created new keys (were missing): $notFound")
            }

            // Ghi lại file
            val transformer = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
                // Giữ đúng format SharedPreferences
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
            }
            transformer.transform(DOMSource(doc), StreamResult(file))

            DebugLogger.d(TAG, "XML written: ${file.length()} bytes")
            true

        } catch (e: Exception) {
            DebugLogger.e(TAG, "modifyPrefsXml exception", e)
            false
        }
    }

    // ── Đọc vị trí hiện tại (debug) ──────────────────────────────

    /**
     * Đọc vị trí window hiện tại của 1 package (để debug)
     */
    fun readCurrentPosition(packageName: String): WindowRect? {
        val xmlPath = "/data/data/$packageName/shared_prefs/${packageName}_preferences.xml"
        val tmpPath = "$TMP_DIR/${packageName.replace('.', '_')}_read.xml"

        return try {
            RootShell.exec("mkdir -p $TMP_DIR && cp '$xmlPath' '$tmpPath' && chmod 644 '$tmpPath'")
            val file = File(tmpPath)
            if (!file.exists()) return null

            val doc  = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val root = doc.documentElement
            val vals = mutableMapOf<String, Int>()

            val nodes = root.childNodes
            for (i in 0 until nodes.length) {
                val node = nodes.item(i) as? Element ?: continue
                val name = node.getAttribute("name")
                if (name.startsWith("app_cloner_current_window_")) {
                    vals[name] = node.getAttribute("value").toIntOrNull() ?: 0
                }
            }

            RootShell.execSilent("rm -f '$tmpPath'")

            if (vals.size >= 4) {
                val rect = WindowRect(
                    left   = vals["app_cloner_current_window_left"]   ?: 0,
                    top    = vals["app_cloner_current_window_top"]     ?: 0,
                    right  = vals["app_cloner_current_window_right"]   ?: 0,
                    bottom = vals["app_cloner_current_window_bottom"]  ?: 0
                )
                DebugLogger.d(TAG, "readCurrentPosition($packageName) = $rect")
                rect
            } else {
                DebugLogger.w(TAG, "readCurrentPosition($packageName): only ${vals.size}/4 keys found")
                null
            }

        } catch (e: Exception) {
            DebugLogger.e(TAG, "readCurrentPosition exception", e)
            null
        }
    }

    // ── Screen size ───────────────────────────────────────────────

    fun getScreenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        // Luôn trả về landscape: chiều dài > chiều rộng
        val screenW = maxOf(dm.widthPixels, dm.heightPixels)
        val screenH = minOf(dm.widthPixels, dm.heightPixels)
        DebugLogger.d(TAG, "getScreenSize raw=${dm.widthPixels}x${dm.heightPixels} → landscape=${screenW}x${screenH}")
        return Pair(screenW, screenH)
    }
}
