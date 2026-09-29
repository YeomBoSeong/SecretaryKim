package com.ybs.assistant.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.ybs.assistant.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_SMS_RESULTS = 20
private val SMS_INBOX_URI: Uri = Uri.parse("content://sms/inbox")

/**
 * 문자 조회/발송 도구. READ_SMS/SEND_SMS는 온보딩에서 미리 받지 않고, 해당 도구를 처음 쓰려는
 * 순간 [onRequestPermission]으로 요청한다. send_sms는 실제로 문자를 발송하는(되돌릴 수 없는)
 * 동작이라, 기존 write_file/delete_file과 같은 [confirm]으로 사용자 확인을 한 번 더 받는다.
 */
object SmsToolExecutor {

    private fun dateTimeFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    suspend fun execute(
        context: Context,
        name: String,
        input: JSONObject,
        onRequestPermission: suspend (String) -> Boolean,
        confirm: suspend (String) -> Boolean,
    ): String = when (name) {
        "search_sms" -> {
            if (ensurePermission(context, Manifest.permission.READ_SMS, onRequestPermission)) {
                searchSms(context, input.optString("query", ""))
            } else {
                context.getString(R.string.sms_permission_denied)
            }
        }
        "send_sms" -> {
            if (ensurePermission(context, Manifest.permission.SEND_SMS, onRequestPermission)) {
                sendSms(context, input.optString("phone_number", ""), input.optString("message", ""), confirm)
            } else {
                context.getString(R.string.sms_permission_denied)
            }
        }
        else -> context.getString(R.string.fs_unknown_tool, name)
    }

    private suspend fun ensurePermission(
        context: Context,
        permission: String,
        onRequestPermission: suspend (String) -> Boolean,
    ): Boolean {
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) return true
        return onRequestPermission(permission)
    }

    private fun searchSms(context: Context, query: String): String {
        val projection = arrayOf("address", "body", "date")
        val selection = if (query.isBlank()) null else "body LIKE ?"
        val args = if (query.isBlank()) null else arrayOf("%$query%")
        val format = dateTimeFormat()

        val results = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(SMS_INBOX_URI, projection, selection, args, "date DESC")
        }.getOrNull()?.use { cursor ->
            val addressIdx = cursor.getColumnIndex("address")
            val bodyIdx = cursor.getColumnIndex("body")
            val dateIdx = cursor.getColumnIndex("date")
            while (cursor.moveToNext() && results.size < MAX_SMS_RESULTS) {
                val address = cursor.getString(addressIdx) ?: continue
                val body = cursor.getString(bodyIdx) ?: ""
                val date = format.format(Date(cursor.getLong(dateIdx)))
                results.add("[$date] $address: $body")
            }
        }

        if (results.isEmpty()) return context.getString(R.string.sms_no_results, query)
        return results.joinToString("\n")
    }

    private suspend fun sendSms(context: Context, phoneNumber: String, message: String, confirm: suspend (String) -> Boolean): String {
        if (phoneNumber.isBlank()) return context.getString(R.string.sms_phone_required)
        if (message.isBlank()) return context.getString(R.string.sms_message_required)

        val confirmMessage = context.getString(R.string.sms_send_confirm_message, phoneNumber, message)
        if (!confirm(confirmMessage)) return context.getString(R.string.sms_send_declined)

        return runCatching {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            context.getString(R.string.sms_send_success, phoneNumber)
        }.getOrElse { context.getString(R.string.sms_send_failed, it.message) }
    }
}
