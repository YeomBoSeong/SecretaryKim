package com.ybs.assistant.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ybs.assistant.R
import com.ybs.assistant.data.SecureKeyStore

/**
 * BYOK(자기 키 지참) 설정 화면. 권한 설정 다음 단계로 최초 1회 등장하고(onBack == null),
 * 설정 화면의 "API 키 설정" 메뉴로도 동일한 화면이 재진입된다(onBack != null).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeySetupScreen(
    keyStore: SecureKeyStore,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    var apiKey by remember { mutableStateOf(keyStore.getClaudeApiKey().orEmpty()) }
    var revealed by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text(stringResource(R.string.api_key_setup_title)) },
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
                    text = stringResource(R.string.api_key_setup_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(10.dp)
            }
            Text(
                text = stringResource(R.string.api_key_setup_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(20.dp)

            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                    keyStore.setClaudeApiKey(it)
                },
                label = { Text(stringResource(R.string.api_key_setup_label)) },
                placeholder = { Text(stringResource(R.string.api_key_setup_placeholder)) },
                singleLine = true,
                visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(
                                if (revealed) R.string.api_key_setup_hide else R.string.api_key_setup_reveal,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(6.dp)
            Text(
                text = stringResource(R.string.api_key_setup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            if (onBack == null) {
                Spacer(24.dp)
                Button(
                    onClick = onDone,
                    enabled = apiKey.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_next))
                }
            }
        }
    }
}
