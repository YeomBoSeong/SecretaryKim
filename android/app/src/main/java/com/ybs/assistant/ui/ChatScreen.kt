package com.ybs.assistant.ui

import com.ybs.assistant.BuildConfig
import com.ybs.assistant.network.BackendConfig
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.text.style.TextAlign
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ybs.assistant.R
import com.ybs.assistant.accessibility.AccessibilityPermission
import com.ybs.assistant.accessibility.AssistantAccessibilityService
import com.ybs.assistant.data.ChatMessage
import com.ybs.assistant.data.FileAttachmentUtils
import com.ybs.assistant.data.ImageUtils
import com.ybs.assistant.data.PreparedFileAttachment
import com.ybs.assistant.data.Role
import com.ybs.assistant.data.TodoItem
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenGuide: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val listState = rememberLazyListState()
    val displayItems = remember(uiState.messages) { groupChatMessages(uiState.messages) }

    var accessibilityEnabled by remember {
        mutableStateOf(AccessibilityPermission.isEnabled(context, AssistantAccessibilityService::class.java))
    }

    // 연락처/캘린더/문자 도구가 처음 호출될 때 ChatViewModel이 pendingPermissionRequest에 필요한
    // 권한 문자열을 채워 넣는다 — 여기서 그 값을 감지해 실제 시스템 권한 다이얼로그를 띄우고,
    // 결과를 다시 ViewModel로 돌려줘서 멈춰있던 도구 실행을 이어가게 한다.
    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.resolvePermissionRequest(granted) }
    LaunchedEffect(viewModel.pendingPermissionRequest.value) {
        val permission = viewModel.pendingPermissionRequest.value ?: return@LaunchedEffect
        runtimePermissionLauncher.launch(permission)
    }

    val voiceInputNotSupportedText = stringResource(R.string.voice_input_not_supported)
    val voiceInputPrompt = stringResource(R.string.voice_input_prompt)
    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spokenText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spokenText.isNullOrBlank()) {
            val existing = viewModel.inputText.value
            viewModel.inputText.value = if (existing.isBlank()) spokenText else "$existing $spokenText"
            viewModel.sendMessage(viaVoice = true)
        }
    }
    fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, voiceInputPrompt)
        }
        try {
            speechRecognizerLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, voiceInputNotSupportedText, Toast.LENGTH_SHORT).show()
        }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchVoiceInput() }
    val onVoiceInputClick = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchVoiceInput()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val attachPermissionDeniedText = stringResource(R.string.attach_permission_denied)
    val attachCoroutineScope = rememberCoroutineScope()
    fun prepareAttachment(uri: Uri) {
        attachCoroutineScope.launch {
            val prepared = withContext(Dispatchers.IO) { ImageUtils.prepareChatAttachment(context, uri) }
            viewModel.pendingImageUri.value = prepared
        }
    }
    var pendingCameraCaptureUri by remember { mutableStateOf<Uri?>(null) }
    val chatCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val uri = pendingCameraCaptureUri
        if (uri != null && success) {
            prepareAttachment(uri)
        }
    }
    val chatCameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val uri = createChatCaptureUri(context)
            pendingCameraCaptureUri = uri
            chatCameraLauncher.launch(uri)
        } else {
            Toast.makeText(context, attachPermissionDeniedText, Toast.LENGTH_SHORT).show()
        }
    }
    val onAttachCamera = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            val uri = createChatCaptureUri(context)
            pendingCameraCaptureUri = uri
            chatCameraLauncher.launch(uri)
        } else {
            chatCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val chatGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) prepareAttachment(uri)
    }
    val onAttachGallery = {
        chatGalleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val attachFileUnsupportedText = stringResource(R.string.attach_file_unsupported)
    fun prepareFileAttachment(uri: Uri) {
        attachCoroutineScope.launch {
            val prepared = withContext(Dispatchers.IO) { FileAttachmentUtils.prepareChatFileAttachment(context, uri) }
            if (prepared == null) {
                Toast.makeText(context, attachFileUnsupportedText, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.pendingFileAttachment.value = prepared
            }
        }
    }
    val chatFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) prepareFileAttachment(uri)
    }
    val onAttachFile = { chatFileLauncher.launch("*/*") }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled =
                    AccessibilityPermission.isEnabled(context, AssistantAccessibilityService::class.java)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    LaunchedEffect(displayItems.size) {
        if (displayItems.isNotEmpty()) {
            listState.animateScrollToItem(displayItems.size - 1)
        }
    }

    viewModel.pendingRiskyActionConfirmation.value?.let { message ->
        RiskyActionConfirmDialog(
            message = message,
            onAllow = { viewModel.resolveRiskyActionConfirmation(true) },
            onDeny = { viewModel.resolveRiskyActionConfirmation(false) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = stringResource(R.string.app_name),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "v${BuildConfig.VERSION_CODE}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        Text(
                            text = uiState.model,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearChat() }) {
                        Icon(Icons.Filled.AddCircleOutline, contentDescription = stringResource(R.string.cd_new_chat))
                    }
                    IconButton(onClick = onOpenGuide) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.cd_help))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.cd_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                modifier = Modifier.padding(0.dp),
            )
        },
        modifier = Modifier
            .fillMaxSize(),
        // 하단(내비게이션 바/키보드) 여백은 ChatInputBar가 직접 계산해서 처리하므로, 여기서는 상단·좌우만 반영한다.
        // 그대로 두면 Scaffold가 내비게이션 바 몫을 한 번 더 얹어 입력창이 이중으로 떠 보인다.
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            viewModel.updateInfo.value?.let { update ->
                UpdateAvailableBanner(
                    versionName = update.versionName,
                    onOpenDownloadPage = {
                        val url = update.downloadUrl.ifBlank { BackendConfig.DOWNLOAD_PAGE_URL }
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    onDismiss = { viewModel.updateInfo.value = null },
                )
            }
            if (!accessibilityEnabled) {
                AccessibilityBanner(
                    onEnableClick = {
                        AccessibilityPermission.openAccessibilitySettings(
                            context,
                            AssistantAccessibilityService::class.java,
                        )
                    },
                )
            }
            if (uiState.isAutomating) {
                AutomationCautionBanner()
            }
            if (uiState.todos.isNotEmpty()) {
                TodoChecklistCard(todos = uiState.todos)
            }

            Box(modifier = Modifier.weight(1f)) {
                if (uiState.messages.isEmpty()) {
                    EmptyState(onOpenGuide = onOpenGuide)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(displayItems) { index, item ->
                            when (item) {
                                is ChatListItem.Single -> MessageBubble(
                                    message = item.message,
                                    isSpeaking = viewModel.speakingContent.value != null &&
                                        viewModel.speakingContent.value == item.message.content,
                                    onToggleSpeak = { viewModel.toggleSpeak(item.message.content) },
                                )
                                is ChatListItem.ToolLogGroup -> ToolLogGroupBubble(
                                    logs = item.logs,
                                    isLive = uiState.isLoading && index == displayItems.lastIndex,
                                )
                            }
                        }
                        if (uiState.isLoading) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(18.dp).widthIn(min = 18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    if (uiState.isAutomating) {
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Surface(
                                            onClick = { viewModel.stopAutomation() },
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                            shape = RoundedCornerShape(9.dp),
                                            modifier = Modifier
                                                .height(18.dp)
                                                .offset(y = 9.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .padding(horizontal = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Stop,
                                                    contentDescription = null,
                                                    modifier = Modifier.height(9.dp),
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(stringResource(R.string.action_stop), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ChatInputBar(
                text = viewModel.inputText.value,
                onTextChange = { viewModel.inputText.value = it },
                onSend = { viewModel.sendMessage() },
                onVoiceInput = onVoiceInputClick,
                enabled = !uiState.isLoading,
                pendingImageUri = viewModel.pendingImageUri.value,
                onAttachCamera = onAttachCamera,
                onAttachGallery = onAttachGallery,
                onRemoveAttachment = { viewModel.clearPendingImage() },
                pendingFileAttachment = viewModel.pendingFileAttachment.value,
                onAttachFile = onAttachFile,
                onRemoveFileAttachment = { viewModel.clearPendingFile() },
            )
        }
    }
}

@Composable
private fun UpdateAvailableBanner(
    versionName: String,
    onOpenDownloadPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.update_available_body, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_available_dismiss))
            }
            TextButton(onClick = onOpenDownloadPage) {
                Text(stringResource(R.string.update_available_button))
            }
        }
    }
}

@Composable
private fun AccessibilityBanner(onEnableClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.banner_accessibility_needed),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.padding(4.dp))
            TextButton(onClick = onEnableClick) {
                Text(stringResource(R.string.action_turn_on_in_settings))
            }
        }
    }
}

@Composable
private fun AutomationCautionBanner() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            text = stringResource(R.string.automation_caution_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** 모델이 todo_write로 관리하는 현재 작업의 할 일 목록을 보여준다. */
@Composable
private fun TodoChecklistCard(todos: List<TodoItem>) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            todos.forEach { todo ->
                val icon = when (todo.status) {
                    "completed" -> "✅"
                    "in_progress" -> "🔄"
                    else -> "⬜"
                }
                Text(
                    text = "$icon ${todo.content}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** write_file/delete_file 같은 파일 작업이나 결제·구매처럼 되돌리기 어려운 화면 동작을 AI가
 * 실행하기 전에 사용자에게 허용 여부를 묻는다. */
@Composable
private fun RiskyActionConfirmDialog(
    message: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(stringResource(R.string.dialog_risky_action_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onAllow) {
                Text(stringResource(R.string.action_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onDeny) {
                Text(stringResource(R.string.action_deny))
            }
        },
    )
}

@Composable
private fun EmptyState(onOpenGuide: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.empty_state_ask_anything),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.empty_state_ai_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onOpenGuide) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_view_guide))
            }
        }
    }
}

/** 채팅 목록을 그릴 때 쓰는 단위. 연속된 TOOL_LOG 메시지는 ToolLogGroup 하나로 묶어서, 스텝이
 * 많은 작업이라도 채팅이 로그로 도배되지 않고 접었다 펼 수 있게 한다. */
private sealed class ChatListItem {
    data class Single(val message: ChatMessage) : ChatListItem()
    data class ToolLogGroup(val logs: List<ChatMessage>) : ChatListItem()
}

private fun groupChatMessages(messages: List<ChatMessage>): List<ChatListItem> {
    val result = mutableListOf<ChatListItem>()
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        if (msg.role == Role.TOOL_LOG) {
            val group = mutableListOf<ChatMessage>()
            while (i < messages.size && messages[i].role == Role.TOOL_LOG) {
                group.add(messages[i])
                i++
            }
            result.add(ChatListItem.ToolLogGroup(group))
        } else {
            result.add(ChatListItem.Single(msg))
            i++
        }
    }
    return result
}

/** 실제 도구 호출(화면 확인/tap/swipe 등) 1건당 로그가 3줄(결과, 입력·출력 토큰, 캐시 사용량)
 * 찍히므로, 로그 줄 수(logs.size)를 그대로 "단계 수"로 보여주면 실제 단계보다 3배 부풀려 보인다.
 * 결과 줄(🔍/🔧로 시작)만 세어서 실제 단계 수를 계산한다. */
private fun countRealSteps(logs: List<ChatMessage>): Int =
    logs.count { it.content.startsWith("🔍") || it.content.startsWith("🔧") }

@Composable
private fun ToolLogGroupBubble(logs: List<ChatMessage>, isLive: Boolean) {
    var expanded by remember(logs.first()) { mutableStateOf(false) }
    val showDetails = expanded || isLive

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = stringResource(R.string.tool_log_group_summary, countRealSteps(logs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Icon(
                imageVector = if (showDetails) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp),
            )
        }
        if (showDetails) {
            Column {
                logs.forEach { log ->
                    Text(
                        text = log.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, isSpeaking: Boolean, onToggleSpeak: () -> Unit) {
    when (message.role) {
        Role.TOOL_LOG -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .widthIn(max = 340.dp)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Role.USER -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(horizontalAlignment = Alignment.End) {
                    message.imageUri?.let { uri ->
                        AttachedImageThumbnail(
                            uri = uri,
                            maxDimension = 220.dp,
                            modifier = Modifier.padding(bottom = if (message.content.isNotBlank()) 4.dp else 0.dp),
                        )
                    }
                    message.fileName?.let { name ->
                        AttachedFileChip(
                            fileName = name,
                            onRemove = null,
                            modifier = Modifier.padding(bottom = if (message.content.isNotBlank()) 4.dp else 0.dp),
                        )
                    }
                    if (message.content.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.widthIn(max = 300.dp),
                        ) {
                            Text(
                                text = message.content,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        val clipboardManager = LocalClipboardManager.current
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(message.content)) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_reply),
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }
        Role.ASSISTANT -> {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Text(
                        text = message.content,
                        modifier = Modifier
                            .widthIn(max = 340.dp)
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        color = if (message.isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onBackground,
                    )
                }
                if (message.content.isNotBlank() && !message.isError) {
                    val clipboardManager = LocalClipboardManager.current
                    Row(modifier = Modifier.padding(start = 2.dp)) {
                        IconButton(onClick = { clipboardManager.setText(AnnotatedString(message.content)) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_reply),
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                        IconButton(onClick = onToggleSpeak, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = if (isSpeaking) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = stringResource(if (isSpeaking) R.string.cd_stop_tts else R.string.cd_play_tts),
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceInput: () -> Unit,
    enabled: Boolean,
    pendingImageUri: Uri?,
    onAttachCamera: () -> Unit,
    onAttachGallery: () -> Unit,
    onRemoveAttachment: () -> Unit,
    pendingFileAttachment: PreparedFileAttachment?,
    onAttachFile: () -> Unit,
    onRemoveFileAttachment: () -> Unit,
) {
    val canSend = enabled && (text.isNotBlank() || pendingImageUri != null || pendingFileAttachment != null)
    var attachMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            // 키보드가 열리면 ime, 닫혀 있으면 내비게이션 바 — 각각 따로 더하면(imePadding + navigationBarsPadding)
            // 전환 중에 두 여백이 겹쳐 입력창이 필요 이상으로 떠 보인다. union으로 더 큰 쪽 하나만 적용한다.
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (pendingImageUri != null) {
                Box(modifier = Modifier.padding(bottom = 8.dp)) {
                    AttachedImageThumbnail(uri = pendingImageUri, maxDimension = 72.dp)
                    IconButton(
                        onClick = onRemoveAttachment,
                        modifier = Modifier
                            .offset(x = 6.dp, y = (-6).dp)
                            .size(22.dp)
                            .background(color = MaterialTheme.colorScheme.secondary, shape = RoundedCornerShape(50)),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_remove_attachment),
                            tint = MaterialTheme.colorScheme.onSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            if (pendingFileAttachment != null) {
                AttachedFileChip(
                    fileName = pendingFileAttachment.fileName,
                    onRemove = onRemoveFileAttachment,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { attachMenuExpanded = true }, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = stringResource(R.string.cd_attach_image),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    DropdownMenu(
                        expanded = attachMenuExpanded,
                        onDismissRequest = { attachMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_menu_camera)) },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                            onClick = {
                                attachMenuExpanded = false
                                onAttachCamera()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_menu_gallery)) },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            onClick = {
                                attachMenuExpanded = false
                                onAttachGallery()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.attach_menu_file)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
                            onClick = {
                                attachMenuExpanded = false
                                onAttachFile()
                            },
                        )
                    }
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    TextField(
                        value = text,
                        onValueChange = onTextChange,
                        placeholder = { Text(stringResource(R.string.input_placeholder)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp, max = 160.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                        trailingIcon = {
                            IconButton(onClick = onVoiceInput, enabled = enabled) {
                                Icon(
                                    imageVector = Icons.Filled.Mic,
                                    contentDescription = stringResource(R.string.cd_voice_input),
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        },
                    )
                }
                Spacer(modifier = Modifier.padding(4.dp))
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .background(
                            color = if (canSend) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(50),
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = stringResource(R.string.cd_send),
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun AttachedImageThumbnail(uri: Uri, maxDimension: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val imageBitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ImageUtils.decodeScaledBitmap(context, uri, maxDimension = 768)?.asImageBitmap() }
                .getOrNull()
        }
    }
    val bitmap = imageBitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .widthIn(max = maxDimension)
                .heightIn(max = maxDimension)
                .clip(RoundedCornerShape(14.dp)),
        )
    } else {
        Box(
            modifier = modifier
                .size(maxDimension)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
private fun AttachedFileChip(fileName: String, onRemove: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            if (onRemove != null) {
                Spacer(modifier = Modifier.width(2.dp))
                IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_remove_attachment),
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** 채팅 첨부용 카메라 촬영 파일을 앱 캐시 디렉터리에 만들고, 다른 앱(카메라)이 쓸 수 있게 FileProvider Uri로 노출한다. */
private fun createChatCaptureUri(context: android.content.Context): Uri {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "chat_capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
