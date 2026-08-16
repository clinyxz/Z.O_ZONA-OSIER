/**
 * ZONA-OSIER — Accessibility Service (Full Implementation).
 * Membaca node tree layar, gesture injection, event processing.
 *
 * Fitur:
 * - readScreenContent(): traverse rootInActiveWindow → teks terstruktur
 * - tapAt(x, y): gesture injection via GestureDescription
 * - swipe(x1,y1,x2,y2,duration): swipe gesture
 * - scrollForward()/scrollBackward(): scroll gestures
 * - Event processing: TYPE_WINDOW_STATE_CHANGED untuk deteksi app switch
 * - Vision fallback: screenshot via Shizuku jika node tree kosong
 * - Singleton instance untuk ScreenReadTool binding
 */
package com.zonaosier.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zonaosier.agent.tools.ScreenReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Data class untuk event yang diterima dari AccessibilityService.
 * Di-emit sebagai SharedFlow agar UI dan agent bisa subscribe.
 */
data class ScreenEvent(
    val eventType: Int,
    val packageName: String?,
    val className: String?,
    val text: String?,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Hasil pembacaan layar terstruktur.
 */
data class ScreenContent(
    val appPackage: String?,
    val windowTitle: String?,
    val nodes: List<ScreenNode>,
    val rawText: String,
    val isEmpty: Boolean
)

/**
 * Satu node dari tree layar.
 */
data class ScreenNode(
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewIdResourceName: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isFocusable: Boolean,
    val bounds: android.graphics.Rect?,
    val children: List<ScreenNode>
)

class ZonaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ZonaAccessibility"
        private const val MAX_NODE_DEPTH = 30
        private const val MAX_TEXT_LENGTH = 10000
        private const val GESTURE_TIMEOUT_MS = 3000L

        /**
         * Singleton instance — di-bind saat service berjalan.
         * ScreenReadTool mengakses ini untuk membaca layar.
         */
        @Volatile
        private var instance: ZonaAccessibilityService? = null

        /**
         * Object singleton untuk binding ke ScreenReadTool.
         */
        val screenReader: ScreenReader = object : ScreenReader {
            override fun readScreen(): String? {
                val svc = instance ?: return null
                val content = svc.buildScreenContent()
                return if (content.isEmpty) null else content.rawText
            }

            override fun isAvailable(): Boolean = instance != null
        }

        /** Cek apakah service aktif. */
        fun isServiceActive(): Boolean = instance != null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Flow untuk event layar — UI dan agent bisa subscribe.
     * Buffer 50 event, DROP_OLDEST agar tidak OOM.
     */
    private val _screenEvents = MutableSharedFlow<ScreenEvent>(
        replay = 0,
        extraBufferCapacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val screenEvents: SharedFlow<ScreenEvent> = _screenEvents.asSharedFlow()

    /**
     * Flow untuk konteks layar terkini — dipakai agent.
     */
    private val _currentScreen = MutableSharedFlow<ScreenContent>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val currentScreen: SharedFlow<ScreenContent> = _currentScreen.asSharedFlow()

    /** Shizuku screenshot fallback — di-inject dari luar. */
    private var screenshotProvider: (() -> Bitmap?)? = null

    fun setScreenshotProvider(provider: (() -> Bitmap?)?) {
        screenshotProvider = provider
    }

    // ==================== Lifecycle ====================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Konfigurasi service info secara programatis
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_DEFAULT
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            }

            notificationTimeout = 100L
        }

        // Kirim screen content pertama kali
        serviceScope.launch {
            val content = buildScreenContent()
            _currentScreen.emit(content)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val screenEvent = ScreenEvent(
            eventType = event.eventType,
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            text = event.text?.joinToString(" ")
        )

        serviceScope.launch {
            _screenEvents.emit(screenEvent)
        }

        // Update screen content pada event yang relevan
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                serviceScope.launch {
                    val content = buildScreenContent()
                    _currentScreen.emit(content)
                }
            }
        }
    }

    override fun onInterrupt() {
        // Service di-interrupt oleh sistem
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ==================== Screen Reading ====================

    /**
     * Baca konten layar saat ini.
     * Mengembalikan teks terstruktur dari node tree.
     * Jika node tree kosong (Canvas/Custom View), fallback ke screenshot.
     */
    fun readScreenContent(): String {
        val content = buildScreenContent()
        if (content.isEmpty) {
            // Fallback: screenshot via Shizuku
            val bitmap = screenshotProvider?.invoke()
            if (bitmap != null) {
                return "[SCREENSHOT_FALLBACK] Bitmap ${bitmap.width}x${bitmap.height} — konten visual, tidak bisa dibaca sebagai teks. Gunakan VLM untuk analisis."
            }
            return ""
        }
        return content.rawText
    }

    /**
     * Bangun ScreenContent dari node tree.
     */
    fun buildScreenContent(): ScreenContent {
        val root = rootInActiveWindow
        if (root == null) {
            return ScreenContent(
                appPackage = null,
                windowTitle = null,
                nodes = emptyList(),
                rawText = "",
                isEmpty = true
            )
        }

        val nodes = mutableListOf<ScreenNode>()
        val textBuilder = StringBuilder()
        val appPackage = root.packageName?.toString()

        traverseNode(root, depth = 0, nodes, textBuilder)

        val rawText = textBuilder.toString().trim()
        return ScreenContent(
            appPackage = appPackage,
            windowTitle = root.windowTitle?.toString(),
            nodes = nodes,
            rawText = rawText,
            isEmpty = rawText.isBlank()
        ).also {
            root.recycle()
        }
    }

    /**
     * Traverse node tree secara rekursif.
     * Mengumpulkan teks dan metadata dari setiap node.
     */
    private fun traverseNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        collector: MutableList<ScreenNode>,
        textBuilder: StringBuilder
    ) {
        if (depth > MAX_NODE_DEPTH) return

        // Kumpulkan teks dari node ini
        val nodeText = node.text?.toString()
        val nodeContentDesc = node.contentDescription?.toString()
        val hintText = node.hintText?.toString()

        val hasText = !nodeText.isNullOrBlank() ||
                !nodeContentDesc.isNullOrBlank() ||
                !hintText.isNullOrBlank()

        if (hasText) {
            val lines = mutableListOf<String>()
            if (!nodeText.isNullOrBlank()) lines.add(nodeText)
            if (!nodeContentDesc.isNullOrBlank()) lines.add(nodeContentDesc)
            if (!hintText.isNullOrBlank()) lines.add(hintText)

            // Prefix dengan view ID jika ada
            val viewId = node.viewIdResourceName
            val prefix = if (!viewId.isNullOrBlank()) "[$viewId] " else ""
            textBuilder.appendLine("$prefix${lines.joinToString(" | ")}")
        }

        // Bangun ScreenNode
        val childNodes = mutableListOf<ScreenNode>()
        val screenNode = ScreenNode(
            text = nodeText,
            contentDescription = nodeContentDesc,
            className = node.className?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isFocusable = node.isFocusable,
            bounds = if (node.isVisibleToUser) {
                android.graphics.Rect().also { node.getBoundsInScreen(it) }
            } else null,
            children = childNodes // akan diisi setelah traverse
        )
        collector.add(screenNode)

        // Rekursi ke children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, depth + 1, childNodes, textBuilder)
            child.recycle()
        }

        // Batas panjang output
        if (textBuilder.length > MAX_TEXT_LENGTH) {
            textBuilder.delete(MAX_TEXT_LENGTH, textBuilder.length)
            textBuilder.append("\n...(truncated)")
        }
    }

    // ==================== Gesture Injection ====================

    /**
     * Eksekusi tap gesture di koordinat (x, y).
     * Menggunakan GestureDescription API (API 24+).
     *
     * @return true jika gesture berhasil di-dispatch.
     */
    fun tapAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(x, y)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Eksekusi tap di tengah node yang memiliki teks tertentu.
     * Berguna untuk agent: "tap pada tombol Kirim".
     *
     * @return true jika node ditemukan dan tap berhasil.
     */
    fun tapOnText(text: String, exactMatch: Boolean = false): Boolean {
        val root = rootInActiveWindow ?: return false
        val result = findNodeByText(root, text, exactMatch)
        if (result != null) {
            val rect = android.graphics.Rect()
            result.getBoundsInScreen(rect)
            val centerX = rect.centerX().toFloat()
            val centerY = rect.centerY().toFloat()
            result.recycle()
            return tapAt(centerX, centerY)
        }
        root.recycle()
        return false
    }

    /**
     * Eksekusi swipe gesture.
     *
     * @param x1,y1 Koordinat awal.
     * @param x2,y2 Koordinat akhir.
     * @param durationMs Durasi swipe dalam milidetik.
     * @return true jika gesture berhasil di-dispatch.
     */
    fun swipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 300L
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Scroll ke depan (jari ke atas = content naik).
     * Menggunakan dimensi layar.
     *
     * @return true jika berhasil.
     */
    fun scrollForward(): Boolean {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val startY = h * 0.7f
        val endY = h * 0.3f
        return swipe(w / 2f, startY, w / 2f, endY, 400L)
    }

    /**
     * Scroll ke belakang (jari ke bawah = content turun).
     *
     * @return true jika berhasil.
     */
    fun scrollBackward(): Boolean {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val startY = h * 0.3f
        val endY = h * 0.7f
        return swipe(w / 2f, startY, w / 2f, endY, 400L)
    }

    /**
     * Ketik teks ke node yang sedang fokus.
     * Gunakan setText() jika node editable, fallback ke ACTION_SET_TEXT.
     *
     * @return true jika berhasil.
     */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } else {
            false
        }

        focused.recycle()
        return result
    }

    // ==================== Node Search ====================

    /**
     * Cari node pertama yang mengandung teks tertentu.
     */
    fun findNodeByText(
        text: String,
        exactMatch: Boolean = false
    ): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val result = findNodeByTextRecursive(root, text, exactMatch)
        if (result == null) root.recycle()
        return result
    }

    /**
     * Cari semua node yang mengandung teks tertentu.
     */
    fun findAllNodesByText(
        text: String,
        exactMatch: Boolean = false
    ): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesByTextRecursive(root, text, exactMatch, results)
        return results
    }

    /**
     * Cari node dengan view ID tertentu.
     */
    fun findNodeByViewId(viewId: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        return nodes.firstOrNull() ?: root.recycle().let { null }
    }

    // ==================== Private Helpers ====================

    private fun findNodeByTextRecursive(
        node: AccessibilityNodeInfo,
        text: String,
        exactMatch: Boolean,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (depth > MAX_NODE_DEPTH) return null

        val nodeText = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val hintText = node.hintText?.toString()

        val match = if (exactMatch) {
            nodeText == text || contentDesc == text || hintText == text
        } else {
            nodeText?.contains(text, ignoreCase = true) == true ||
                    contentDesc?.contains(text, ignoreCase = true) == true ||
                    hintText?.contains(text, ignoreCase = true) == true
        }

        if (match) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByTextRecursive(child, text, exactMatch, depth + 1)
            if (found != null) return found
            child.recycle()
        }

        return null
    }

    private fun findAllNodesByTextRecursive(
        node: AccessibilityNodeInfo,
        text: String,
        exactMatch: Boolean,
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        if (depth > MAX_NODE_DEPTH) return

        val nodeText = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()

        val match = if (exactMatch) {
            nodeText == text || contentDesc == text
        } else {
            nodeText?.contains(text, ignoreCase = true) == true ||
                    contentDesc?.contains(text, ignoreCase = true) == true
        }

        if (match) {
            // Salin info penting sebelum node di-recycle parent
            val copy = AccessibilityNodeInfo.obtain(node)
            results.add(copy)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllNodesByTextRecursive(child, text, exactMatch, results, depth + 1)
            child.recycle()
        }
    }

    /**
     * Cek apakah accessibility permission diberikan.
     * Dipanggil dari UI untuk menampilkan status.
     */
    fun checkAccessibilityEnabled(): Boolean {
        return try {
            android.provider.Settings.Secure.getInt(
                contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED
            ) == 1
        } catch (_: Exception) {
            false
        }
    }
}
