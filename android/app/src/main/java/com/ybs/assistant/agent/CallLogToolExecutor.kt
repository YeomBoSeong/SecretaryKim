package com.ybs.assistant.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import com.ybs.assistant.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_CALL_LOG_RESULTS = 20

/**
 * 통화 기록 조회 도구. READ_CALL_LOG는 온보딩에서 미리 받지 않고, 이 도구를 처음 쓰려는 순간
 * [onRequestPermission]으로 요청한다.
 */
object CallLogToolExecutor {

    suspend fun execute(
        context: Context,
        name: String,
        input: JSONObject,
        onRequestPermission: suspend (String) -> Boolean,
    ): String {
        if (name != "search_call_log") return context.getString(R.string.fs_unknown_tool, name)
        if (!ensurePermission(context, onRequestPermission)) {
            return context.getString(R.string.call_log_permission_denied)
        }
        return searchCallLog(context, input.optString("query", ""))
    }

    private suspend fun ensurePermission(context: Context, onRequestPermission: suspend (String) -> Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return onRequestPermission(Manifest.permission.READ_CALL_LOG)
    }

    private fun typeLabel(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "수신"
        CallLog.Calls.OUTGOING_TYPE -> "발신"
        CallLog.Calls.MISSED_TYPE -> "부재중"
        CallLog.Calls.REJECTED_TYPE -> "거절"
        else -> "기타"
    }

    private fun searchCallLog(context: Context, query: String): String {
        val projection = arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE)
        val selection = if (query.isBlank()) null else "${CallLog.Calls.CACHED_NAME} LIKE ? OR ${CallLog.Calls.NUMBER} LIKE ?"
        val args = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        val results = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, selection, args, "${CallLog.Calls.DATE} DESC")
        }.getOrNull()?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
            val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
            while (cursor.moveToNext() && results.size < MAX_CALL_LOG_RESULTS) {
                val name = cursor.getString(nameIdx)
                val number = cursor.getString(numberIdx) ?: continue
                val date = format.format(Date(cursor.getLong(dateIdx)))
                val duration = cursor.getLong(durationIdx)
                val type = typeLabel(cursor.getInt(typeIdx))
                val who = if (name.isNullOrBlank()) number else "$name ($number)"
                results.add("[$date] $type · $who · ${duration}초")
            }
        }

        if (results.isEmpty()) return context.getString(R.string.call_log_no_results, query)
        return results.joinToString("\n")
    }
}
