package com.ybs.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.ybs.assistant.accessibility.AccessibilityPermission
import com.ybs.assistant.accessibility.AssistantAccessibilityService
import com.ybs.assistant.data.SecureKeyStore
import com.ybs.assistant.notification.NotificationPermission
import com.ybs.assistant.ui.ApiKeySetupScreen
import com.ybs.assistant.ui.ChatScreen
import com.ybs.assistant.ui.ChatViewModel
import com.ybs.assistant.ui.GuideScreen
import com.ybs.assistant.ui.PermissionSetupScreen
import com.ybs.assistant.ui.SettingsScreen
import com.ybs.assistant.ui.theme.AIAssistantTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val keyStore = SecureKeyStore(applicationContext)
        val viewModel = ChatViewModel(application, keyStore)

        setContent {
            AIAssistantTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(viewModel = viewModel, keyStore = keyStore)
                }
            }
        }
    }
}

private enum class Screen { PERMISSION_SETUP, API_KEY_SETUP, CHAT, SETTINGS, GUIDE }

private fun permissionsMissing(context: android.content.Context): Boolean =
    !AccessibilityPermission.isEnabled(context, AssistantAccessibilityService::class.java) ||
        !NotificationPermission.isGranted(context) ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED

@Composable
private fun AppRoot(viewModel: ChatViewModel, keyStore: SecureKeyStore) {
    val context = LocalContext.current

    // 권한 설정 다음엔 반드시 Claude API 키 설정을 거쳐야 채팅으로 들어간다(BYOK — 자기 키 지참).
    var screen by remember {
        mutableStateOf(
            when {
                !keyStore.isOnboardingDone() && permissionsMissing(context) -> Screen.PERMISSION_SETUP
                !keyStore.hasClaudeApiKey() -> Screen.API_KEY_SETUP
                else -> Screen.CHAT
            }
        )
    }

    fun afterPermissions() {
        keyStore.setOnboardingDone(true)
        screen = if (!keyStore.hasClaudeApiKey()) Screen.API_KEY_SETUP else Screen.CHAT
    }

    BackHandler(enabled = screen == Screen.SETTINGS || screen == Screen.GUIDE) {
        screen = Screen.CHAT
    }

    when (screen) {
        Screen.PERMISSION_SETUP -> PermissionSetupScreen(
            onRequiredPermissionsGranted = { afterPermissions() },
        )
        Screen.API_KEY_SETUP -> ApiKeySetupScreen(
            keyStore = keyStore,
            onDone = { screen = Screen.CHAT },
        )
        Screen.CHAT -> ChatScreen(
            viewModel = viewModel,
            onOpenSettings = { screen = Screen.SETTINGS },
            onOpenGuide = { screen = Screen.GUIDE },
        )
        Screen.SETTINGS -> SettingsScreen(
            keyStore = keyStore,
            onBack = { screen = Screen.CHAT },
        )
        Screen.GUIDE -> GuideScreen(
            onBack = { screen = Screen.CHAT },
        )
    }
}
