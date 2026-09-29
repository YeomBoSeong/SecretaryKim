package com.ybs.assistant.agent

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.ybs.assistant.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private const val MAX_CALENDAR_RESULTS = 50
private const val DEFAULT_RANGE_DAYS = 7L
private const val DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm"

/**
 * 일정 조회/추가/수정/삭제 도구. READ_CALENDAR/WRITE_CALENDAR는 온보딩에서 미리 받지 않고, 해당
 * 도구를 처음 쓰려는 순간 [onRequestPermission]으로 요청한다. delete_calendar_event는 되돌리기
 * 어려운 작업이라 실행 전에 [confirm]으로 사용자 확인을 받는다.
 *
 * add_calendar_event는 이벤트를 넣기 전에 어느 캘린더(_ID)에 넣을지부터 조회해야 해서 WRITE_CALENDAR만으로는
 * 부족하고 READ_CALENDAR도 같이 있어야 한다 — 이걸 빠뜨리면 "쓰기 권한은 있는데 캘린더 목록 조회 자체가
 * 조용히 실패해서 쓰기 가능한 캘린더가 없다"는 오해를 부르는 오류가 난다(실제로 겪은 버그).
 */
object CalendarToolExecutor {

    private fun dateTimeFormat() = SimpleDateFormat(DATE_TIME_PATTERN, Locale.getDefault())

    suspend fun execute(
        context: Context,
        name: String,
        input: JSONObject,
        onRequestPermission: suspend (String) -> Boolean,
        confirm: suspend (String) -> Boolean,
    ): String {
        val permissions = when (name) {
            "add_calendar_event" -> listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            "update_calendar_event", "delete_calendar_event" -> listOf(Manifest.permission.WRITE_CALENDAR)
            else -> listOf(Manifest.permission.READ_CALENDAR)
        }
        if (!ensurePermissions(context, permissions, onRequestPermission)) {
            return context.getString(R.string.calendar_permission_denied)
        }
        return when (name) {
            "list_calendar_events" -> listEvents(context, input.optString("from", ""), input.optString("to", ""))
            "add_calendar_event" -> addEvent(
                context,
                input.optString("title", ""),
                input.optString("start", ""),
                input.optString("end", ""),
                input.optString("description", ""),
            )
            "update_calendar_event" -> updateEvent(
                context,
                input.optLong("event_id", -1),
                input.optString("title", ""),
                input.optString("start", ""),
                input.optString("end", ""),
                input.optString("description", ""),
            )
            "delete_calendar_event" -> deleteEvent(context, input.optLong("event_id", -1), confirm)
            else -> context.getString(R.string.fs_unknown_tool, name)
        }
    }

    private suspend fun ensurePermissions(
        context: Context,
        permissions: List<String>,
        onRequestPermission: suspend (String) -> Boolean,
    ): Boolean {
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                if (!onRequestPermission(permission)) return false
            }
        }
        return true
    }

    private fun listEvents(context: Context, from: String, to: String): String {
        val format = dateTimeFormat()
        val beginMillis = from.takeIf { it.isNotBlank() }
            ?.let { runCatching { format.parse(it)?.time }.getOrNull() }
            ?: System.currentTimeMillis()
        val endMillis = to.takeIf { it.isNotBlank() }
            ?.let { runCatching { format.parse(it)?.time }.getOrNull() }
            ?: (beginMillis + DEFAULT_RANGE_DAYS * 24 * 60 * 60 * 1000)

        val instancesUri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, beginMillis)
            ContentUris.appendId(this, endMillis)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
        )

        val results = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(instancesUri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
        }.getOrNull()?.use { cursor ->
            val idIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
            val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
            val locationIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            while (cursor.moveToNext() && results.size < MAX_CALENDAR_RESULTS) {
                val eventId = cursor.getLong(idIdx)
                val title = cursor.getString(titleIdx) ?: continue
                val begin = format.format(cursor.getLong(beginIdx))
                val end = format.format(cursor.getLong(endIdx))
                val location = if (locationIdx >= 0) cursor.getString(locationIdx) else null
                val suffix = if (location.isNullOrBlank()) "" else ", $location"
                results.add("[ID:$eventId] $title ($begin ~ $end$suffix)")
            }
        }

        if (results.isEmpty()) {
            return context.getString(R.string.calendar_no_events, format.format(beginMillis), format.format(endMillis))
        }
        return results.joinToString("\n")
    }

    private fun findWritableCalendarId(context: Context): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        return runCatching {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)
        }.getOrNull()?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)) else null
        }
    }

    private fun addEvent(context: Context, title: String, start: String, end: String, description: String): String {
        if (title.isBlank()) return context.getString(R.string.calendar_title_required)
        val format = dateTimeFormat()
        val startMillis = runCatching { format.parse(start)?.time }.getOrNull()
            ?: return context.getString(R.string.calendar_invalid_datetime, start)
        val endMillis = runCatching { format.parse(end)?.time }.getOrNull()
            ?: return context.getString(R.string.calendar_invalid_datetime, end)
        val calendarId = findWritableCalendarId(context)
            ?: return context.getString(R.string.calendar_no_writable_calendar)

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (description.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, description)
        }
        runCatching { context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }.getOrNull()
            ?: return context.getString(R.string.calendar_add_failed)
        return context.getString(R.string.calendar_add_success, title, start, end)
    }

    private fun updateEvent(context: Context, eventId: Long, title: String, start: String, end: String, description: String): String {
        if (eventId <= 0) return context.getString(R.string.calendar_event_id_required)
        val format = dateTimeFormat()
        val values = ContentValues()
        if (title.isNotBlank()) values.put(CalendarContract.Events.TITLE, title)
        if (start.isNotBlank()) {
            val startMillis = runCatching { format.parse(start)?.time }.getOrNull()
                ?: return context.getString(R.string.calendar_invalid_datetime, start)
            values.put(CalendarContract.Events.DTSTART, startMillis)
        }
        if (end.isNotBlank()) {
            val endMillis = runCatching { format.parse(end)?.time }.getOrNull()
                ?: return context.getString(R.string.calendar_invalid_datetime, end)
            values.put(CalendarContract.Events.DTEND, endMillis)
        }
        if (description.isNotBlank()) values.put(CalendarContract.Events.DESCRIPTION, description)
        if (values.size() == 0) return context.getString(R.string.calendar_update_nothing)

        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val updated = runCatching { context.contentResolver.update(uri, values, null, null) }.getOrDefault(0)
        return if (updated > 0) {
            context.getString(R.string.calendar_update_success, eventId)
        } else {
            context.getString(R.string.calendar_event_not_found, eventId)
        }
    }

    private suspend fun deleteEvent(context: Context, eventId: Long, confirm: suspend (String) -> Boolean): String {
        if (eventId <= 0) return context.getString(R.string.calendar_event_id_required)
        if (!confirm(context.getString(R.string.calendar_delete_confirm_message, eventId))) {
            return context.getString(R.string.calendar_delete_declined)
        }

        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val deleted = runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0)
        return if (deleted > 0) {
            context.getString(R.string.calendar_delete_success, eventId)
        } else {
            context.getString(R.string.calendar_event_not_found, eventId)
        }
    }
}
