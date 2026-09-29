package com.ybs.assistant.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ybs.assistant.R

/** 화면 캡처 이미지가 세로로 너무 길어 보이지 않도록, 실제 크기의 80%로 줄여서 가운데 정렬한다. */
private const val GUIDE_IMAGE_WIDTH_FRACTION = 0.8f

/** 권한 안내 등에 쓰이는 실제 화면 캡처 이미지 + 설명 한 세트. */
@Composable
fun GuideImageStep(imageRes: Int, captionRes: Int) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(GUIDE_IMAGE_WIDTH_FRACTION),
        ) {
            Image(
                painter = painterResource(imageRes),
                contentDescription = stringResource(captionRes),
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(captionRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun StepSpacer() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
}

private val accessibilityGuideSteps = listOf(
    R.drawable.guide_a11y_banner to R.string.guide_caption_a11y_1,
    R.drawable.guide_a11y_list to R.string.guide_caption_a11y_list,
    R.drawable.guide_a11y_apps to R.string.guide_caption_a11y_2,
    R.drawable.guide_a11y_toggle_off to R.string.guide_caption_a11y_3,
    R.drawable.guide_a11y_confirm to R.string.guide_caption_a11y_4,
    R.drawable.guide_a11y_toggle_on to R.string.guide_caption_a11y_5,
)

@Composable
fun AccessibilityGuideContent() {
    Column {
        Text(stringResource(R.string.guide_section_accessibility_body), style = MaterialTheme.typography.bodyMedium)
        StepSpacer()

        val pagerState = rememberPagerState(pageCount = { accessibilityGuideSteps.size })
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
            val (imageRes, captionRes) = accessibilityGuideSteps[page]
            GuideImageStep(imageRes, captionRes)
        }
        StepSpacer()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            repeat(accessibilityGuideSteps.size) { index ->
                val selected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f),
                        ),
                )
            }
        }
    }
}

@Composable
fun NotificationGuideContent() {
    Column {
        Text(stringResource(R.string.guide_section_notification_body), style = MaterialTheme.typography.bodyMedium)
        StepSpacer()
        GuideImageStep(R.drawable.guide_notif_dialog, R.string.guide_caption_notif_2)
    }
}

@Composable
fun MicGuideContent() {
    Column {
        Text(stringResource(R.string.guide_section_mic_body), style = MaterialTheme.typography.bodyMedium)
        StepSpacer()
        GuideImageStep(R.drawable.guide_mic_dialog, R.string.guide_caption_mic_1)
    }
}

@Composable
fun CameraGuideContent() {
    Column {
        Text(stringResource(R.string.guide_section_camera_body), style = MaterialTheme.typography.bodyMedium)
        StepSpacer()
        GuideImageStep(R.drawable.guide_camera_dialog, R.string.guide_caption_camera_1)
    }
}

@Composable
fun FileAccessGuideContent() {
    Column {
        Text(stringResource(R.string.guide_section_file_access_body), style = MaterialTheme.typography.bodyMedium)
        StepSpacer()
        GuideImageStep(R.drawable.guide_file_access_dialog, R.string.guide_caption_file_access_1)
    }
}

@Composable
fun SettingsLocationGuideContent() {
    Column {
        Text(stringResource(R.string.guide_section_settings_body), style = MaterialTheme.typography.bodyMedium)
        StepSpacer()
        GuideImageStep(R.drawable.guide_settings_gear, R.string.guide_caption_settings)
    }
}

@Composable
fun CautionGuideContent() {
    Text(stringResource(R.string.guide_section_caution_body), style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun ReturnGuideContent() {
    Text(stringResource(R.string.guide_section_return_body), style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun ApiKeySettingsGuideContent() {
    Text(stringResource(R.string.guide_section_api_key_body), style = MaterialTheme.typography.bodyMedium)
}
