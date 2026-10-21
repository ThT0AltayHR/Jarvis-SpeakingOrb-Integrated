package com.jarvis.assistant.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** A locally stored conversation. Turns stay on-device in app preferences. */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Yeni sohbet",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val turns: List<ChatTurn> = emptyList(),
    val isPinned: Boolean = false,
    val isTitleCustom: Boolean = false
) {
    val preview: String
        get() = turns.lastOrNull()?.let { turn ->
            turn.jarvisResponse.ifBlank { turn.userTranscript }.replace(Regex("\\s+"), " ").take(110)
        } ?: "Henüz mesaj yok"

    val formattedUpdatedAt: String
        get() = SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(updatedAt))
}
