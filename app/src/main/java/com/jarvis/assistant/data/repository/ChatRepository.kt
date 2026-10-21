package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.model.ChatSession
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps the active conversation and recent archived conversations on-device.
 * Current-turn writes from both voice and text paths are merged to avoid the
 * old blank-response placeholder appearing as a duplicate message.
 */
class ChatRepository(private val preferences: AppPreferences) {
    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    @Volatile private var activeSessionId: String = ""
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistenceMutex = Mutex()
    private val persistenceRevision = AtomicLong(0L)

    init {
        val loaded = preferences.loadChatSessions().sortedWith(compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt })
        if (loaded.isEmpty()) {
            // One-time migration: keep the previously stored flat chat history.
            val oldTurns = preferences.loadChatHistory()
            val migrated = ChatSession(
                id = preferences.activeChatSessionId.ifBlank { UUID.randomUUID().toString() },
                title = oldTurns.firstOrNull()?.userTranscript?.take(42)?.ifBlank { "Eski sohbet" } ?: "Eski sohbet",
                turns = oldTurns
            )
            activeSessionId = migrated.id
            _turns.value = oldTurns
            _sessions.value = listOf(migrated)
            persistAll()
        } else {
            _sessions.value = loaded.take(MAX_SESSIONS)
            activeSessionId = preferences.activeChatSessionId
                .takeIf { id -> loaded.any { it.id == id } }
                ?: loaded.first().id
            _turns.value = loaded.first { it.id == activeSessionId }.turns
            persistAll()
        }
    }

    @Synchronized
    fun addTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()
        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return

        val current = _turns.value.toMutableList()
        val last = current.lastOrNull()
        if (last != null && last.userTranscript == cleanUser &&
            (last.jarvisResponse.isBlank() || last.jarvisResponse == "(Audio response)") && cleanJarvis.isNotBlank()) {
            current[current.lastIndex] = last.copy(jarvisResponse = cleanJarvis)
        } else if (last != null && last.userTranscript == cleanUser && last.jarvisResponse == cleanJarvis) {
            return
        } else {
            current.add(ChatTurn(
                userTranscript = cleanUser.ifEmpty { "(Sesli sorgu)" },
                jarvisResponse = cleanJarvis
            ))
        }

        while (current.size > MAX_TURNS) current.removeAt(0)
        _turns.value = current
        updateActiveSession(current)
        persistAll()
    }

    /** Archives the active chat and opens a clean session. */
    @Synchronized
    fun startNewConversation(): ChatSession {
        if (_turns.value.isEmpty()) {
            _sessions.value.firstOrNull { it.id == activeSessionId }?.let { return it }
        }
        updateActiveSession(_turns.value)
        val newSession = ChatSession()
        activeSessionId = newSession.id
        _turns.value = emptyList()
        _sessions.value = (listOf(newSession) + _sessions.value).take(MAX_SESSIONS)
        preferences.activeChatSessionId = activeSessionId
        persistAll()
        return newSession
    }

    @Synchronized
    fun openConversation(sessionId: String): Boolean {
        if (sessionId == activeSessionId) return true
        val target = _sessions.value.firstOrNull { it.id == sessionId } ?: return false
        updateActiveSession(_turns.value)
        activeSessionId = target.id
        _turns.value = target.turns
        preferences.activeChatSessionId = target.id
        persistAll()
        return true
    }


    @Synchronized
    fun renameConversation(sessionId: String, newTitle: String): Boolean {
        val title = newTitle.replace(Regex("\\s+"), " ").trim().take(64)
        if (title.isBlank()) return false
        val target = _sessions.value.firstOrNull { it.id == sessionId } ?: return false
        _sessions.value = _sessions.value.map { if (it.id == sessionId) it.copy(title = title, isTitleCustom = true, updatedAt = System.currentTimeMillis()) else it }
            .sortedWith(compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt })
        persistAll()
        return target.id == sessionId
    }

    @Synchronized
    fun togglePinned(sessionId: String): Boolean {
        if (_sessions.value.none { it.id == sessionId }) return false
        _sessions.value = _sessions.value.map { if (it.id == sessionId) it.copy(isPinned = !it.isPinned) else it }
            .sortedWith(compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt })
        persistAll()
        return _sessions.value.first { it.id == sessionId }.isPinned
    }

    @Synchronized
    fun deleteConversation(sessionId: String): Boolean {
        if (_sessions.value.none { it.id == sessionId }) return false
        val remaining = _sessions.value.filterNot { it.id == sessionId }
        if (sessionId == activeSessionId) {
            val replacement = remaining.firstOrNull() ?: ChatSession()
            activeSessionId = replacement.id
            _turns.value = replacement.turns
            _sessions.value = if (remaining.any { it.id == replacement.id }) remaining else listOf(replacement) + remaining
            preferences.activeChatSessionId = activeSessionId
        } else {
            _sessions.value = remaining
        }
        if (_sessions.value.isEmpty()) {
            val fresh = ChatSession()
            activeSessionId = fresh.id
            _turns.value = emptyList()
            _sessions.value = listOf(fresh)
            preferences.activeChatSessionId = fresh.id
        }
        persistAll()
        return true
    }

    @Synchronized
    fun clearHistory() {
        val fresh = ChatSession()
        activeSessionId = fresh.id
        _turns.value = emptyList()
        _sessions.value = listOf(fresh)
        preferences.activeChatSessionId = fresh.id
        preferences.clearChatHistory()
        preferences.clearChatSessions()
        persistAll()
    }

    @Synchronized
    private fun updateActiveSession(turns: List<ChatTurn>) {
        val old = _sessions.value.firstOrNull { it.id == activeSessionId }
        val firstUser = turns.firstOrNull { it.userTranscript.isNotBlank() }?.userTranscript.orEmpty()
        val title = when {
            old?.isTitleCustom == true -> old.title
            turns.isEmpty() -> old?.title?.takeIf { it.isNotBlank() } ?: "Yeni sohbet"
            firstUser.isNotBlank() -> firstUser.replace(Regex("\\s+"), " ").take(42)
            else -> old?.title ?: "Sohbet"
        }
        val updated = ChatSession(
            id = activeSessionId.ifBlank { UUID.randomUUID().toString() },
            title = title,
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            turns = turns.toList(),
            isPinned = old?.isPinned ?: false,
            isTitleCustom = old?.isTitleCustom ?: false
        )
        activeSessionId = updated.id
        _sessions.value = (listOf(updated) + _sessions.value.filterNot { it.id == updated.id })
            .sortedWith(compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt })
            .take(MAX_SESSIONS)
        preferences.activeChatSessionId = activeSessionId
    }

    private fun persistAll() {
        // Gson serialization of long code transcripts can be expensive. Snapshot state quickly,
        // then serialize/write on a single IO critical section instead of blocking the UI thread.
        val revision = persistenceRevision.incrementAndGet()
        val sessionsSnapshot = _sessions.value.toList()
        val activeIdSnapshot = activeSessionId
        persistenceScope.launch {
            persistenceMutex.withLock {
                if (revision != persistenceRevision.get()) return@withLock
                preferences.saveChatSessions(sessionsSnapshot)
                preferences.activeChatSessionId = activeIdSnapshot
            }
        }
    }

    companion object {
        private const val MAX_TURNS = 100
        private const val MAX_SESSIONS = 20
    }
}
