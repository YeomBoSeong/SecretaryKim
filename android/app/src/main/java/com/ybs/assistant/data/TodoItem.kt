package com.ybs.assistant.data

/** todo_write 도구로 모델이 관리하는 할 일 목록의 항목 하나. status: "pending" | "in_progress" | "completed". */
data class TodoItem(
    val content: String,
    val status: String,
)
