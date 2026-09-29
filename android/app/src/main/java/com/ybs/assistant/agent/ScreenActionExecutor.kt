package com.ybs.assistant.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ybs.assistant.R
import com.ybs.assistant.accessibility.AssistantAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * 채팅 대화 도중 Claude/GPT/Gemini가 화면 조작 tool을 호출했을 때 실제로
 * AccessibilityService를 통해 실행하는 공용 로직. (adb_test/agent_loop.py 포팅)
 */
object ScreenActionExecutor {
    const val RESIZE_MAX_DIM = 1024

    suspend fun captureAndResize(service: AssistantAccessibilityService): Pair<ByteArray, Float> =
        withContext(Dispatchers.Default) {
            val raw = service.captureScreenshotPng()
            val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            val maxDim = max(bitmap.width, bitmap.height)
            var scale = 1f
            var resized = bitmap
            if (maxDim > RESIZE_MAX_DIM) {
                scale = RESIZE_MAX_DIM.toFloat() / maxDim
                resized = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true,
                )
            }
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray() to scale
        }

    /** 화면 조작 tool을 실행하고 사람이 읽을 로그 문자열을 반환한다. */
    suspend fun execute(
        context: Context,
        service: AssistantAccessibilityService,
        name: String,
        input: JSONObject,
        scale: Float,
    ): String {
        // 필수 값이 없거나 형식이 안 맞으면 get*()가 JSONException을 던진다 — optInt() 등으로
        // 조용히 0/빈값을 받으면, 좌표가 비어도 (0,0)을 실제 탭 좌표로 실행해버려서 모델이
        // 계속 같은 무의미한 지점을 반복 조작하는 버그로 이어진다(호출부에서 이 예외를 잡아
        // 모델에게 재시도를 요청한다).
        return when (name) {
            "tap" -> {
                val x = (input.getInt("x") / scale).toInt()
                val y = (input.getInt("y") / scale).toInt()
                service.tap(x, y)
                context.getString(R.string.tool_result_tap, x, y)
            }
            "swipe" -> {
                val x1 = (input.getInt("x1") / scale).toInt()
                val y1 = (input.getInt("y1") / scale).toInt()
                val x2 = (input.getInt("x2") / scale).toInt()
                val y2 = (input.getInt("y2") / scale).toInt()
                val duration = input.getLong("duration_ms")
                service.swipe(x1, y1, x2, y2, duration)
                context.getString(R.string.tool_result_swipe, x1, y1, x2, y2)
            }
            "type_text" -> {
                val text = input.getString("text")
                service.typeText(text)
                context.getString(R.string.tool_result_type_text, text)
            }
            "key_event" -> {
                val keycode = input.getString("keycode")
                service.keyEvent(keycode)
                context.getString(R.string.tool_result_key_event, keycode)
            }
            "wait" -> {
                val seconds = input.getDouble("seconds")
                delay((seconds * 1000).toLong())
                context.getString(R.string.tool_result_wait, seconds.toString())
            }
            else -> context.getString(R.string.tool_result_unknown, name)
        }
    }
}
