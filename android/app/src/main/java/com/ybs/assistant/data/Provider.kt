package com.ybs.assistant.data

import android.net.Uri

enum class Role { USER, ASSISTANT, TOOL_LOG }

data class ChatMessage(
    val role: Role,
    val content: String,
    val isError: Boolean = false,
    /** 사용자가 채팅에 첨부한 이미지 (앱 캐시에 저장된 파일 Uri). */
    val imageUri: Uri? = null,
    /** 사용자가 첨부한 PDF (원본 그대로 API에 전송). 텍스트 계열 첨부는 fileUri 없이 fileExtractedText만 채워진다. */
    val fileUri: Uri? = null,
    val fileName: String? = null,
    val fileMimeType: String? = null,
    /** PDF가 아닌 첨부(txt/md/csv/json/docx/xlsx)에서 미리 뽑아둔 텍스트. */
    val fileExtractedText: String? = null,
)
