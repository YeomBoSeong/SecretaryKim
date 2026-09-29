package com.ybs.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ybs.assistant.R
import com.ybs.assistant.data.SecureKeyStore

private enum class SettingsSection { SYSTEM_PROMPT, VOICE_REPLY, PERMISSIONS, API_KEY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    keyStore: SecureKeyStore,
    onBack: () -> Unit,
) {
    var selected by remember { mutableStateOf<SettingsSection?>(null) }

    BackHandler(enabled = selected != null) { selected = null }

    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            if (initialState == null) {
                // 메뉴에서 상세로: 오른쪽에서 탭이 밀고 들어온다.
                (slideInHorizontally(tween(220)) { it } togetherWith slideOutHorizontally(tween(220)) { -it / 4 })
            } else {
                // 상세에서 메뉴로 돌아갈 때: 반대 방향으로 밀려난다.
                (slideInHorizontally(tween(220)) { -it / 4 } togetherWith slideOutHorizontally(tween(220)) { it })
            }
        },
        label = "settings-nav",
    ) { section ->
        when (section) {
            null -> SettingsMenuScreen(
                onBack = onBack,
                onSelectSystemPrompt = { selected = SettingsSection.SYSTEM_PROMPT },
                onSelectVoiceReply = { selected = SettingsSection.VOICE_REPLY },
                onSelectPermissions = { selected = SettingsSection.PERMISSIONS },
                onSelectApiKey = { selected = SettingsSection.API_KEY },
            )
            SettingsSection.API_KEY -> ApiKeySetupScreen(
                keyStore = keyStore,
                onDone = { selected = null },
                onBack = { selected = null },
            )
            SettingsSection.PERMISSIONS -> PermissionSetupScreen(
                onRequiredPermissionsGranted = { selected = null },
                onBack = { selected = null },
            )
            SettingsSection.VOICE_REPLY -> VoiceReplySettingsScreen(
                keyStore = keyStore,
                onBack = { selected = null },
            )
            SettingsSection.SYSTEM_PROMPT -> SystemPromptSettingsScreen(
                keyStore = keyStore,
                onBack = { selected = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMenuScreen(
    onBack: () -> Unit,
    onSelectSystemPrompt: () -> Unit,
    onSelectVoiceReply: () -> Unit,
    onSelectPermissions: () -> Unit,
    onSelectApiKey: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            SettingsMenuButton(stringResource(R.string.settings_menu_api_key), onSelectApiKey)
            Spacer(modifier = Modifier.height(12.dp))
            SettingsMenuButton(stringResource(R.string.system_prompt_title), onSelectSystemPrompt)
            Spacer(modifier = Modifier.height(12.dp))
            SettingsMenuButton(stringResource(R.string.settings_menu_voice_reply), onSelectVoiceReply)
            Spacer(modifier = Modifier.height(12.dp))
            SettingsMenuButton(stringResource(R.string.settings_menu_permissions), onSelectPermissions)
        }
    }
}

@Composable
private fun SettingsMenuButton(title: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.height(16.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SystemPromptSettingsScreen(
    keyStore: SecureKeyStore,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.system_prompt_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            SystemPromptForm(keyStore = keyStore)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceReplySettingsScreen(
    keyStore: SecureKeyStore,
    onBack: () -> Unit,
) {
    var autoSpeak by remember { mutableStateOf(keyStore.isAutoSpeakEnabled()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_menu_voice_reply)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_voice_reply_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_auto_speak_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_auto_speak_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Switch(
                        checked = autoSpeak,
                        onCheckedChange = {
                            autoSpeak = it
                            keyStore.setAutoSpeakEnabled(it)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemPromptForm(keyStore: SecureKeyStore) {
    var prompt by remember { mutableStateOf(keyStore.getSystemPrompt()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.system_prompt_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = prompt,
            onValueChange = {
                prompt = it
                keyStore.setSystemPrompt(it)
            },
            label = { Text(stringResource(R.string.system_prompt_label)) },
            placeholder = { Text(stringResource(R.string.system_prompt_placeholder)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.system_prompt_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}
