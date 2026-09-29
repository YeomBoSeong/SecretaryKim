package com.ybs.assistant.agent

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import com.ybs.assistant.R
import org.json.JSONObject

private val VOLUME_STREAMS = mapOf(
    "media" to AudioManager.STREAM_MUSIC,
    "ring" to AudioManager.STREAM_RING,
    "alarm" to AudioManager.STREAM_ALARM,
)

/** 손전등/볼륨처럼 별도 권한 없이 시스템 서비스로 바로 제어할 수 있는 도구들. */
object DeviceControlToolExecutor {

    fun execute(context: Context, name: String, input: JSONObject): String = when (name) {
        "set_flashlight" -> setFlashlight(context, input.optBoolean("on", false))
        "set_volume" -> setVolume(context, input.optString("stream", "media"), input.optInt("level_percent", -1))
        else -> context.getString(R.string.fs_unknown_tool, name)
    }

    private fun setFlashlight(context: Context, on: Boolean): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return context.getString(R.string.device_flashlight_unavailable)

        return runCatching {
            manager.setTorchMode(cameraId, on)
            if (on) context.getString(R.string.device_flashlight_on) else context.getString(R.string.device_flashlight_off)
        }.getOrElse { context.getString(R.string.device_flashlight_failed, it.message) }
    }

    private fun setVolume(context: Context, stream: String, levelPercent: Int): String {
        val streamId = VOLUME_STREAMS[stream] ?: return context.getString(R.string.device_volume_invalid_stream)
        if (levelPercent !in 0..100) return context.getString(R.string.device_volume_invalid_level)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(streamId)
        val targetVolume = (maxVolume * levelPercent / 100.0).toInt().coerceIn(0, maxVolume)

        return runCatching {
            audioManager.setStreamVolume(streamId, targetVolume, 0)
            context.getString(R.string.device_volume_success, stream, levelPercent)
        }.getOrElse { context.getString(R.string.device_volume_failed, it.message) }
    }
}
