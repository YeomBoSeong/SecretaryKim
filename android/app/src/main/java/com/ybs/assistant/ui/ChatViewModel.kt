package com.ybs.assistant.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.ybs.assistant.R
import com.ybs.assistant.data.ChatHistoryStore
import com.ybs.assistant.data.ChatMessage
import com.ybs.assistant.data.ModelCatalog
import com.ybs.assistant.data.PreparedFileAttachment
import com.ybs.assistant.data.Role
import com.ybs.assistant.data.SecureKeyStore
import com.ybs.assistant.data.TodoItem
import com.ybs.assistant.BuildConfig
import com.ybs.assistant.network.ChatRepository
import com.ybs.assistant.network.LatestVersionInfo
import com.ybs.assistant.network.UpdateChecker
import com.ybs.assistant.notification.CompletionNotificationManager
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val model: String = ModelCatalog.DEFAULT_MODEL,
    val isLoading: Boolean = false,
    /** 실제로 화면 조작 tool이 실행되고 있는 중인지 (단순 텍스트 대화 로딩과 구분). */
    val isAutomating: Boolean = false,
    /** 자동화 중 가장 최근에 확인한 대상 앱 화면 (진행상황 미리보기용). */
    val latestScreenshot: Bitmap? = null,
    /** 모델이 todo_write로 관리 중인 현재 작업의 할 일 목록. 새 메시지를 보내면 초기화된다. */
    val todos: List<TodoItem> = emptyList(),
)

class ChatViewModel(
    application: Application,
    private val keyStore: SecureKeyStore,
    private val repository: ChatRepository = ChatRepository(),
) : AndroidViewModel(application) {

    private val historyStore = ChatHistoryStore(application)

    private val _uiState = MutableStateFlow(
        ChatUiState(
            messages = historyStore.load(),
        )
    )

    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            uiState.map { it.messages }.distinctUntilChanged().collect { messages ->
                withContext(Dispatchers.IO) { historyStore.save(messages) }
            }
        }
    }

    /** 서버가 더 최신 버전을 알려오면 채워진다 — null이면 최신 상태이거나 아직 확인 전. */
    val updateInfo = mutableStateOf<LatestVersionInfo?>(null)

    init {
        viewModelScope.launch {
            val latest = UpdateChecker.fetchLatestVersion()
            if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) {
                updateInfo.value = latest
            }
        }
    }

    val inputText = mutableStateOf("")
    /** 전송 대기 중인 첨부 이미지 (앱 캐시에 이미 축소·저장된 파일 Uri). */
    val pendingImageUri = mutableStateOf<Uri?>(null)
    /** 전송 대기 중인 첨부 파일 (PDF 원본 또는 텍스트/워드/엑셀에서 미리 추출한 텍스트). */
    val pendingFileAttachment = mutableStateOf<PreparedFileAttachment?>(null)
    /** 현재 재생 중인 답변 텍스트. null이면 아무 것도 재생 중이 아님 — 재생 버튼 아이콘 토글에 쓰인다. */
    val speakingContent = mutableStateOf<String?>(null)
    /** write_file/delete_file 같은 파일 작업이나 결제·구매처럼 되돌리기 어려운 화면 동작 전에 사용자
     * 확인을 구하는 다이얼로그 문구. null이면 대기 중인 확인이 없음. */
    val pendingRiskyActionConfirmation = mutableStateOf<String?>(null)
    /** 연락처/일정/문자 도구를 AI가 처음 쓰려는 순간 요청할 안드로이드 런타임 권한. null이면 대기 중인
     * 요청이 없음 — ChatScreen이 이 값을 보고 실제 시스템 권한 다이얼로그를 띄운다. */
    val pendingPermissionRequest = mutableStateOf<String?>(null)

    private var currentJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeakText: String? = null
    private var riskyActionConfirmationDeferred: CompletableDeferred<Boolean>? = null
    private var permissionRequestDeferred: CompletableDeferred<Boolean>? = null

    /** AI가 파일 쓰기/삭제, 또는 결제·구매처럼 위험한 화면 동작을 하려 할 때 호출된다 — 사용자가
     * 다이얼로그에서 답할 때까지 tool 실행을 멈춰 세운다. */
    private suspend fun confirmRiskyAction(message: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        riskyActionConfirmationDeferred = deferred
        pendingRiskyActionConfirmation.value = message
        return deferred.await()
    }

    fun resolveRiskyActionConfirmation(allowed: Boolean) {
        pendingRiskyActionConfirmation.value = null
        riskyActionConfirmationDeferred?.complete(allowed)
        riskyActionConfirmationDeferred = null
    }

    /** 연락처/일정/문자 도구 실행기가 필요한 권한이 아직 없을 때 호출한다 — ChatScreen이 실제 시스템
     * 권한 다이얼로그를 띄우고 결과를 [resolvePermissionRequest]로 돌려줄 때까지 멈춰 세운다. */
    private suspend fun requestRuntimePermission(permission: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        permissionRequestDeferred = deferred
        pendingPermissionRequest.value = permission
        return deferred.await()
    }

    fun resolvePermissionRequest(granted: Boolean) {
        pendingPermissionRequest.value = null
        permissionRequestDeferred?.complete(granted)
        permissionRequestDeferred = null
    }

    fun clearPendingImage() {
        pendingImageUri.value = null
    }

    fun clearPendingFile() {
        pendingFileAttachment.value = null
    }

    private fun ensureTts(): TextToSpeech {
        var instance = tts
        if (instance == null) {
            instance = TextToSpeech(getApplication()) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.getDefault()
                    pendingSpeakText?.let { text ->
                        pendingSpeakText = null
                        speak(text)
                    }
                }
            }
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    speakingContent.value = null
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    speakingContent.value = null
                }
            })
            tts = instance
        }
        return instance
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        speakingContent.value = text
        val engine = ensureTts()
        if (!ttsReady) {
            pendingSpeakText = text
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
        speakingContent.value = null
    }

    fun toggleSpeak(text: String) {
        if (speakingContent.value == text) stopSpeaking() else speak(text)
    }

    override fun onCleared() {
        super.onCleared()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    fun sendMessage(viaVoice: Boolean = false) {
        val text = inputText.value.trim()
        val imageUri = pendingImageUri.value
        val fileAttachment = pendingFileAttachment.value
        if ((text.isBlank() && imageUri == null && fileAttachment == null) || _uiState.value.isLoading) return
        inputText.value = ""
        pendingImageUri.value = null
        pendingFileAttachment.value = null

        val userMessage = ChatMessage(
            Role.USER,
            text,
            imageUri = imageUri,
            fileUri = fileAttachment?.fileUri,
            fileName = fileAttachment?.fileName,
            fileMimeType = fileAttachment?.mimeType,
            fileExtractedText = fileAttachment?.extractedText,
        )
        _uiState.update { it.copy(messages = it.messages + userMessage, isLoading = true, todos = emptyList()) }

        currentJob = viewModelScope.launch {
            val state = _uiState.value
            try {
                val reply = repository.converse(
                    context = getApplication(),
                    model = state.model,
                    customSystemPrompt = keyStore.getSystemPrompt(),
                    history = _uiState.value.messages,
                    apiKey = keyStore.getClaudeApiKey().orEmpty(),
                    onToolLog = { log ->
                        _uiState.update {
                            it.copy(
                                messages = it.messages + ChatMessage(Role.TOOL_LOG, log),
                                isAutomating = true,
                            )
                        }
                    },
                    onScreenshot = { bitmap ->
                        _uiState.update { it.copy(latestScreenshot = bitmap) }
                    },
                    onConfirmRiskyAction = ::confirmRiskyAction,
                    onRequestPermission = ::requestRuntimePermission,
                    onTodoUpdate = { todos ->
                        _uiState.update { it.copy(todos = todos) }
                    },
                )
                val wasAutomating = _uiState.value.isAutomating
                _uiState.update {
                    it.copy(
                        messages = it.messages + ChatMessage(Role.ASSISTANT, reply),
                        isLoading = false,
                        isAutomating = false,
                        latestScreenshot = null,
                    )
                }
                if (wasAutomating) notifyTaskFinished()
                if (viaVoice && keyStore.isAutoSpeakEnabled()) speak(reply)
            } catch (e: CancellationException) {
                _uiState.update {
                    it.copy(
                        messages = it.messages + ChatMessage(Role.ASSISTANT, getApplication<Application>().getString(R.string.task_cancelled_message)),
                        isLoading = false,
                        isAutomating = false,
                        latestScreenshot = null,
                    )
                }
            } catch (e: Exception) {
                val wasAutomating = _uiState.value.isAutomating
                _uiState.update {
                    it.copy(
                        messages = it.messages + ChatMessage(
                            Role.ASSISTANT,
                            getApplication<Application>().getString(R.string.error_prefix, e.message),
                            isError = true,
                        ),
                        isLoading = false,
                        isAutomating = false,
                        latestScreenshot = null,
                    )
                }
                if (wasAutomating) notifyTaskFinished()
            }
        }
    }

    /**
     * 화면 자동화 작업이 끝날 때마다 호출한다. 사용자가 화면 조작 때문에 다른 앱으로 나가 있는
     * 동안(=김비서 앱이 백그라운드) 끝난 경우에만 알림을 띄운다 — 채팅 화면을 보고 있는 상태에서는
     * 화면에 결과가 바로 보이니 알림이 불필요하다.
     */
    private fun notifyTaskFinished() {
        val appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (!appInForeground) {
            CompletionNotificationManager.show(getApplication())
        }
    }

    fun stopAutomation() {
        currentJob?.cancel()
        resolveRiskyActionConfirmation(false)
    }

    fun clearChat() {
        currentJob?.cancel()
        pendingImageUri.value = null
        pendingFileAttachment.value = null
        stopSpeaking()
        resolveRiskyActionConfirmation(false)
        _uiState.update { it.copy(messages = emptyList(), isLoading = false, isAutomating = false, latestScreenshot = null, todos = emptyList()) }
    }
}
