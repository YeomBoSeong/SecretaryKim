package com.ybs.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ybs.assistant.R
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AssistantAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AssistantAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 폴링 방식(스크린샷 -> 분석 -> 액션)으로만 동작하므로 이벤트 스트림은 사용하지 않는다.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    fun screenSize(): Pair<Int, Int> {
        val metrics: DisplayMetrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    suspend fun captureScreenshotPng(): ByteArray = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hardwareBuffer = result.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                        if (bitmap == null) {
                            hardwareBuffer.close()
                            cont.resumeWithException(IllegalStateException(getString(R.string.error_screenshot_conversion_failed)))
                            return
                        }
                        val softwareBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()
                        val out = ByteArrayOutputStream()
                        softwareBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        cont.resume(out.toByteArray())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    cont.resumeWithException(IllegalStateException(getString(R.string.error_screenshot_capture_failed, errorCode)))
                }
            },
        )
    }

    suspend fun tap(x: Int, y: Int): Boolean = suspendCancellableCoroutine { cont ->
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null,
        )
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50)))
                .build()
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!dispatched && cont.isActive) cont.resume(false)
        }

    fun typeText(text: String): Boolean {
        val node = findFocusedEditableNode() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun keyEvent(keycode: String): Boolean {
        return when (keycode) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "APP_SWITCH" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "ENTER" -> {
                val node = findFocusedEditableNode() ?: return false
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            "DEL" -> {
                val node = findFocusedEditableNode() ?: return false
                val current = node.text?.toString().orEmpty()
                if (current.isEmpty()) return true
                val trimmed = current.substring(0, current.length - 1)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, trimmed)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            else -> false
        }
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private data class TreeElement(val label: String, val cls: String, val clickable: Boolean, val editable: Boolean, val centerX: Int, val centerY: Int)

    /**
     * 스크린샷 대신 접근성 트리를 훑어서 탭 가능한 요소 목록을 텍스트로 돌려준다 (실험적).
     * 이미지를 안 보내니 토큰이 훨씬 적게 들고, 모델이 픽셀 좌표를 눈대중으로 찍을 필요 없이
     * 각 요소의 실제 중심 좌표를 그대로 tap에 쓸 수 있어 더 정확하다 — 단, 스크린샷과 달리
     * 레이아웃 구조만 보일 뿐 색/이미지/실제 렌더링 결과는 알 수 없다.
     */
    fun dumpAccessibilityTree(maxElements: Int = 80): String {
        val root = rootInActiveWindow ?: return "화면 정보를 가져올 수 없어요."
        val results = mutableListOf<TreeElement>()
        val rect = Rect()

        fun visit(node: AccessibilityNodeInfo) {
            if (results.size >= maxElements) return
            if (node.isVisibleToUser) {
                val label = (node.text ?: node.contentDescription ?: node.hintText)?.toString()?.trim().orEmpty()
                val interactive = node.isClickable || node.isLongClickable || node.isCheckable || node.isEditable
                if (label.isNotEmpty() || interactive) {
                    node.getBoundsInScreen(rect)
                    if (!rect.isEmpty) {
                        val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
                        results.add(TreeElement(label.take(60), cls, node.isClickable, node.isEditable, rect.centerX(), rect.centerY()))
                    }
                }
            }
            for (i in 0 until node.childCount) {
                if (results.size >= maxElements) break
                val child = node.getChild(i) ?: continue
                visit(child)
            }
        }
        visit(root)

        if (results.isEmpty()) return "탭 가능한 요소를 찾지 못했어요."
        return results.mapIndexed { i, e ->
            val tag = if (e.editable) "[입력창]" else if (e.clickable) "[탭 가능]" else "[텍스트]"
            "$i: $tag ${e.cls} \"${e.label}\" center=(${e.centerX},${e.centerY})"
        }.joinToString("\n")
    }
}
