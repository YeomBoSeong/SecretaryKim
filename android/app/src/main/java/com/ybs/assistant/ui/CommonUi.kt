package com.ybs.assistant.ui

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Composable
internal fun Spacer(height: Dp) {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(height))
}
