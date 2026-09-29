package com.ybs.assistant.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import com.ybs.assistant.R
import org.json.JSONObject

/**
 * 알람/타이머/지도/이메일 작성처럼, 별도 권한 없이 다른 앱에 인텐트로 넘기기만 하면 되는 도구들.
 * ChatRepository는 Application Context로 이 도구들을 호출하므로 항상 FLAG_ACTIVITY_NEW_TASK가 필요하다.
 */
object IntentToolExecutor {

    fun execute(context: Context, name: String, input: JSONObject): String = when (name) {
        "set_alarm" -> setAlarm(context, input.optInt("hour", -1), input.optInt("minute", -1), input.optString("label", ""))
        "set_timer" -> setTimer(context, input.optInt("seconds", -1), input.optString("label", ""))
        "open_maps" -> openMaps(context, input.optString("query", ""))
        "compose_email" -> composeEmail(
            context,
            input.optString("to", ""),
            input.optString("subject", ""),
            input.optString("body", ""),
        )
        "open_dialer" -> openDialer(context, input.optString("phone_number", ""))
        else -> context.getString(R.string.fs_unknown_tool, name)
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.onFailure { android.util.Log.e("IntentToolExecutor", "startActivity failed for ${intent.action}", it) }
        .getOrElse { false }

    private fun setAlarm(context: Context, hour: Int, minute: Int, label: String): String {
        if (hour !in 0..23 || minute !in 0..59) return context.getString(R.string.intent_alarm_invalid_time)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        return if (start(context, intent)) {
            context.getString(R.string.intent_alarm_success, hour, minute)
        } else {
            context.getString(R.string.intent_no_app_available)
        }
    }

    private fun setTimer(context: Context, seconds: Int, label: String): String {
        if (seconds <= 0) return context.getString(R.string.intent_timer_invalid_length)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        return if (start(context, intent)) {
            context.getString(R.string.intent_timer_success, seconds)
        } else {
            context.getString(R.string.intent_no_app_available)
        }
    }

    private fun openMaps(context: Context, query: String): String {
        if (query.isBlank()) return context.getString(R.string.intent_maps_query_required)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
        return if (start(context, intent)) {
            context.getString(R.string.intent_maps_success, query)
        } else {
            context.getString(R.string.intent_no_app_available)
        }
    }

    private fun openDialer(context: Context, phoneNumber: String): String {
        if (phoneNumber.isBlank()) return context.getString(R.string.intent_dialer_phone_required)
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}"))
        return if (start(context, intent)) {
            context.getString(R.string.intent_dialer_success, phoneNumber)
        } else {
            context.getString(R.string.intent_no_app_available)
        }
    }

    private fun composeEmail(context: Context, to: String, subject: String, body: String): String {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            if (to.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
            if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
        }
        return if (start(context, intent)) {
            context.getString(R.string.intent_email_success)
        } else {
            context.getString(R.string.intent_no_app_available)
        }
    }
}
