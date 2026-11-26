package com.screenpilot.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

data class ScreenCaptureSnapshot(
    val screenshotBase64: String?,
    val uiTreeSummary: String,
    val activePackage: String,
    val visualHash: Int,
    val width: Int,
    val height: Int
)

class ScreenAgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Screen state is sampled on demand by AgentLoopManager
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instanceRef = null
        return super.onUnbind(intent)
    }

    fun getScreenSize(): Pair<Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.coerceAtLeast(1) to dm.heightPixels.coerceAtLeast(1)
        }
    }

    suspend fun captureScreenSnapshot(): ScreenCaptureSnapshot {
        val (width, height) = getScreenSize()
        val activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()
        val uiSummary = dumpUiTree(width, height, activePackage)
        val screenshotResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureScreenshotApi30()
        } else {
            null
        }
        val visualHash = screenshotResult?.second ?: uiSummary.hashCode()
        return ScreenCaptureSnapshot(
            screenshotBase64 = screenshotResult?.first,
            uiTreeSummary = uiSummary,
            activePackage = activePackage,
            visualHash = visualHash,
            width = width,
            height = height
        )
    }

    fun getSafeInstalledAppsHint(): String {
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = packageManager.queryIntentActivities(mainIntent, 0)
        val blocked = listOf(
            "bank", "sber", "tinkoff", "vtb", "alfa", "whatsapp",
            "telegram", "viber", "settings", "настрой", "screenpilot"
        )
        return apps
            .asSequence()
            .map { info ->
                info.loadLabel(packageManager).toString().trim() to info.activityInfo.packageName
            }
            .filter { (label, pkg) ->
                val l = "$label $pkg".lowercase()
                label.isNotBlank() && blocked.none { l.contains(it) }
            }
            .map { it.first }
            .distinct()
            .take(18)
            .joinToString(", ")
    }

    fun findNodeLabelNear(normX: Int, normY: Int): String {
        val root = rootInActiveWindow ?: return ""
        val (w, h) = getScreenSize()
        val px = ((normX / 1000f) * w).toInt()
        val py = ((normY / 1000f) * h).toInt()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rect = Rect()
        var bestLabel = ""
        var bestArea = Int.MAX_VALUE

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(rect)
            if (rect.contains(px, py)) {
                val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim()
                val area = rect.width() * rect.height()
                if (label.isNotBlank() && area < bestArea) {
                    bestArea = area
                    bestLabel = label
                }
            }
        }
        return bestLabel
    }

    private suspend fun captureScreenshotApi30(): Pair<String, Int>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            var result: Pair<String, Int>? = null
                            val hwBuffer = screenshot.hardwareBuffer
                            try {
                                val hwBitmap = Bitmap.wrapHardwareBuffer(
                                    hwBuffer,
                                    screenshot.colorSpace
                                )
                                if (hwBitmap != null) {
                                    val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBitmap.recycle()
                                    if (softBitmap != null) {
                                        val b64 = encodeScaledJpeg(softBitmap)
                                        val hash = computeBitmapSampleHash(softBitmap)
                                        result = b64 to hash
                                        softBitmap.recycle()
                                    }
                                }
                            } catch (_: Throwable) {
                                result = null
                            } finally {
                                hwBuffer.close()
                            }
                            if (cont.isActive) cont.resume(result)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (_: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun computeBitmapSampleHash(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 8 || h < 8) return 0
        var hash = 17
        val stepX = (w / 6).coerceAtLeast(1)
        val stepY = (h / 8).coerceAtLeast(1)
        var y = stepY / 2
        while (y < h) {
            var x = stepX / 2
            while (x < w) {
                // Mask least-significant bits so minor clock/cursor ticks do not break hash matching
                val pixel = bitmap.getPixel(x, y) and 0xF0F0F0
                hash = 31 * hash + pixel
                x += stepX
            }
            y += stepY
        }
        return hash
    }

    private fun encodeScaledJpeg(bitmap: Bitmap): String {
        val maxSide = 640f
        val scale = (maxSide / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
        val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)

        val scaled = if (scale < 0.99f) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 65, baos)
        if (scaled !== bitmap) scaled.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun dumpUiTree(screenW: Int, screenH: Int, pkg: String): String {
        val root = rootInActiveWindow ?: return "UI-дерево недоступно (используй скриншот)."
        val lines = mutableListOf<String>()
        if (pkg.isNotBlank()) {
            lines.add("Активный пакет: $pkg")
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val rect = Rect()

        while (queue.isNotEmpty() && lines.size < 45) {
            val node = queue.removeFirst()
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }

            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(rect)
            if (rect.width() <= 0 || rect.height() <= 0) continue

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = text.ifEmpty { desc }
            val useful = node.isClickable || node.isEditable || node.isScrollable || label.isNotEmpty()
            if (!useful) continue

            val cx = ((rect.centerX().toFloat() / screenW) * 1000f).toInt().coerceIn(0, 1000)
            val cy = ((rect.centerY().toFloat() / screenH) * 1000f).toInt().coerceIn(0, 1000)
            val flags = buildString {
                if (node.isClickable) append("click,")
                if (node.isEditable) append("edit,")
                if (node.isScrollable) append("scroll,")
            }.trimEnd(',')

            val safeLabel = label.replace("\n", " ").take(48)
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
            lines.add("[$cls|$flags] \"$safeLabel\" @ ($cx,$cy)")
        }

        return if (lines.isEmpty()) "Пустое UI-дерево" else lines.joinToString("\n")
    }

    suspend fun performTap(normX: Int, normY: Int): Boolean {
        val (w, h) = getScreenSize()
        val px = (normX / 1000f) * w
        val py = (normY / 1000f) * h
        val path = Path().apply { moveTo(px, py) }
        return dispatchPathGesture(path, 55L)
    }

    suspend fun performSwipe(
        startNormX: Int,
        startNormY: Int,
        endNormX: Int,
        endNormY: Int,
        durationMs: Long = 280L
    ): Boolean {
        val (w, h) = getScreenSize()
        val sx = (startNormX / 1000f) * w
        val sy = (startNormY / 1000f) * h
        val ex = (endNormX / 1000f) * w
        val ey = (endNormY / 1000f) * h
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        return dispatchPathGesture(path, durationMs)
    }

    suspend fun performInputText(text: String, normX: Int, normY: Int): Boolean {
        if (normX in 1..999 && normY in 1..999) {
            performTap(normX, normY)
            delay(180L)
        }

        val targetNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(rootInActiveWindow)
            ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun performNavBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performNavHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openAppOrUrl(rawQuery: String): Boolean {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return false

        if (trimmed.startsWith("http://") ||
            trimmed.startsWith("https://") ||
            trimmed.startsWith("www.")
        ) {
            return openUrl(trimmed)
        }

        return launchAppByQuery(trimmed)
    }

    fun openUrl(rawUrl: String): Boolean {
        val normalized = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://$rawUrl"
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun launchAppByQuery(query: String): Boolean {
        val q = resolveAlias(query.trim().lowercase())
        if (q.isEmpty()) return false

        packageManager.getLaunchIntentForPackage(query.trim())?.let { direct ->
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(direct)
            return true
        }

        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = packageManager.queryIntentActivities(mainIntent, 0)
        val matched = apps.firstOrNull { info ->
            val label = info.loadLabel(packageManager).toString().lowercase()
            val pkg = info.activityInfo.packageName.lowercase()
            label.contains(q) || pkg.contains(q)
        } ?: return false

        val launchIntent =
            packageManager.getLaunchIntentForPackage(matched.activityInfo.packageName)
                ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return true
    }

    private fun resolveAlias(q: String): String {
        return when {
            q.contains("ютуб") -> "youtube"
            q.contains("браузер") || q.contains("хром") -> "chrome"
            q.contains("настрой") -> "settings"
            q.contains("заметк") -> "notes"
            q.contains("погод") -> "weather"
            q.contains("карт") -> "maps"
            q.contains("музык") -> "music"
            q.contains("новост") -> "news"
            else -> q
        }
    }

    private suspend fun dispatchPathGesture(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(20L)))
            .build()

        return suspendCancellableCoroutine { cont ->
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!accepted && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<ScreenAgentAccessibilityService>? = null

        fun getInstance(): ScreenAgentAccessibilityService? = instanceRef?.get()
    }
}
