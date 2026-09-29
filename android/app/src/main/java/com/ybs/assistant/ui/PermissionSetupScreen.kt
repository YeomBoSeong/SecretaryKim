package com.ybs.assistant.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ybs.assistant.R
import com.ybs.assistant.accessibility.AccessibilityPermission
import com.ybs.assistant.accessibility.AssistantAccessibilityService
import com.ybs.assistant.notification.NotificationPermission

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSetupScreen(
    onRequiredPermissionsGranted: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var accessibilityEnabled by remember {
        mutableStateOf(AccessibilityPermission.isEnabled(context, AssistantAccessibilityService::class.java))
    }
    var notificationGranted by remember { mutableStateOf(NotificationPermission.isGranted(context)) }
    var hasRequestedNotification by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationGranted = granted
        hasRequestedNotification = true
    }

    fun requestOrOpenNotificationSettings() {
        val activity = context as? Activity
        val permanentlyDenied = hasRequestedNotification && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        if (permanentlyDenied) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        } else {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled =
                    AccessibilityPermission.isEnabled(context, AssistantAccessibilityService::class.java)
                notificationGranted = NotificationPermission.isGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text(stringResource(R.string.onboarding_permission_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            if (onBack == null) {
                Text(
                    text = stringResource(R.string.onboarding_permission_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(10.dp)
            }
            Text(
                text = stringResource(R.string.onboarding_permission_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (onBack == null) {
                Spacer(6.dp)
                Text(
                    text = stringResource(R.string.onboarding_permission_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(20.dp)

            PermissionCard(
                titleRes = R.string.guide_section_accessibility_title,
                done = accessibilityEnabled,
                required = true,
            ) {
                AccessibilityGuideContent()
                Spacer(14.dp)
                Button(
                    onClick = {
                        AccessibilityPermission.openAccessibilitySettings(
                            context,
                            AssistantAccessibilityService::class.java,
                        )
                    },
                    enabled = !accessibilityEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_turn_on_in_settings))
                }
            }

            Spacer(20.dp)

            PermissionCard(
                titleRes = R.string.guide_section_notification_title,
                done = notificationGranted,
                required = false,
            ) {
                NotificationGuideContent()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(14.dp)
                    OutlinedButton(
                        onClick = { requestOrOpenNotificationSettings() },
                        enabled = !notificationGranted,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_turn_on))
                    }
                }
            }

            // 마이크/카메라/파일 접근/연락처/캘린더/문자 권한은 여기서 미리 받지 않는다 — 해당 기능을
            // 실제로 처음 쓰려는 순간(음성 입력 버튼, 카메라 첨부 버튼, AI의 도구 호출 등)에 그때그때
            // 요청해서, 온보딩 화면에서 한꺼번에 지치는 걸 막는다.

            Spacer(24.dp)

            if (onBack == null) {
                Button(
                    onClick = onRequiredPermissionsGranted,
                    enabled = accessibilityEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_next))
                }
                Spacer(8.dp)
                OutlinedButton(
                    onClick = onRequiredPermissionsGranted,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_skip_for_now))
                }
                Text(
                    text = stringResource(R.string.onboarding_skip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(
    titleRes: Int,
    done: Boolean?,
    required: Boolean,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(done = done, required = required)
            }
            Spacer(12.dp)
            content()
        }
    }
}

@Composable
private fun StatusBadge(done: Boolean?, required: Boolean) {
    val (labelRes, color) = when {
        done == true -> R.string.status_done to MaterialTheme.colorScheme.primary
        required -> R.string.status_required to MaterialTheme.colorScheme.error
        else -> R.string.status_optional to MaterialTheme.colorScheme.secondary
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
