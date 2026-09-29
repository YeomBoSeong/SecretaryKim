package com.ybs.assistant.data

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ChatHistoryStore"

/**
 * 채팅 내역을 로컬 파일에 저장한다. ChatViewModel은 AndroidViewModel을 직접 new해서 만들기 때문에
 * (표준 ViewModelProvider를 안 거침) 액티비티가 재생성되면 인메모리 상태가 그냥 사라진다 — 특히
 * 백그라운드에서 시스템이 프로세스를 죽였다가 사용자가 다시 앱을 열 때 이 경로를 탄다. 그래서
 * 메시지가 바뀔 때마다 여기에 저장해두고 시작할 때 복원한다.
 */
class ChatHistoryStore(context: Context) {
    private val file = File(context.filesDir, "chat_history.json")

    fun load(): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i -> fromJson(array.getJSONObject(i)) }
        }.onFailure { Log.e(TAG, "load failed", it) }.getOrElse { emptyList() }
    }

    fun save(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.forEach { array.put(toJson(it)) }
        runCatching { file.writeText(array.toString()) }.onFailure { Log.e(TAG, "save failed", it) }
    }

    private fun toJson(msg: ChatMessage): JSONObject = JSONObject().apply {
        put("role", msg.role.name)
        put("content", msg.content)
        put("isError", msg.isError)
        msg.imageUri?.let { put("imageUri", it.toString()) }
        msg.fileUri?.let { put("fileUri", it.toString()) }
        msg.fileName?.let { put("fileName", it) }
        msg.fileMimeType?.let { put("fileMimeType", it) }
        msg.fileExtractedText?.let { put("fileExtractedText", it) }
    }

    private fun fromJson(obj: JSONObject): ChatMessage = ChatMessage(
        role = runCatching { Role.valueOf(obj.getString("role")) }.getOrDefault(Role.ASSISTANT),
        content = obj.optString("content"),
        isError = obj.optBoolean("isError", false),
        imageUri = obj.optString("imageUri").takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
        fileUri = obj.optString("fileUri").takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
        fileName = obj.optString("fileName").takeIf { it.isNotBlank() },
        fileMimeType = obj.optString("fileMimeType").takeIf { it.isNotBlank() },
        fileExtractedText = obj.optString("fileExtractedText").takeIf { it.isNotBlank() },
    )
}
