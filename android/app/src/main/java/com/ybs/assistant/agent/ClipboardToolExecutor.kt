package com.ybs.assistant.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.ybs.assistant.R
import org.json.JSONObject

/** 클립보드 읽기/쓰기 — 별도 권한이 필요 없다. */
object ClipboardToolExecutor {

    fun execute(context: Context, name: String, input: JSONObject): String = when (name) {
        "read_clipboard" -> readClipboard(context)
        "write_clipboard" -> writeClipboard(context, input.optString("text", ""))
        else -> context.getString(R.string.fs_unknown_tool, name)
    }

    private fun clipboardManager(context: Context) =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun readClipboard(context: Context): String {
        val manager = clipboardManager(context)
        val clip = if (manager.hasPrimaryClip()) manager.primaryClip else null
        if (clip == null || clip.itemCount == 0) return context.getString(R.string.clipboard_empty)
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
        return if (text.isNullOrBlank()) context.getString(R.string.clipboard_empty) else text
    }

    private fun writeClipboard(context: Context, text: String): String {
        if (text.isBlank()) return context.getString(R.string.clipboard_write_text_required)
        clipboardManager(context).setPrimaryClip(ClipData.newPlainText("김비서", text))
        return context.getString(R.string.clipboard_write_success)
    }
}
