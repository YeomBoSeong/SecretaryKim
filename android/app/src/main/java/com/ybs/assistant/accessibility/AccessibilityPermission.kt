package com.ybs.assistant.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.TextUtils

object AccessibilityPermission {
    fun isEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponent = "${context.packageName}/${serviceClass.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (component in splitter) {
            if (component.equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }

    /**
     * 접근성 설정 화면을 연다. Android는 앱이 스스로 접근성 서비스를 켜는 것을 허용하지 않으므로
     * (그렇게 허용하면 악성 앱이 사용자 동의 없이 화면 전체 제어 권한을 스스로 부여할 수 있게 됨),
     * 설정 화면으로 이동시키는 것까지만 할 수 있다.
     *
     * Android 14(API 34)부터는 서비스 상세(허용) 화면으로 바로 이동하는 공식 딥링크인
     * ACTION_ACCESSIBILITY_DETAILS_SETTINGS + EXTRA_COMPONENT_NAME이 추가됐다. 이걸 우선
     * 시도한다. FLAG_ACTIVITY_NEW_TASK를 주면 삼성 OneUI에서 이 딥링크 대상 화면이 별도의 새
     * 태스크로 뜨자마자 컴포넌트를 처리 못 하고 곧장 finish()해버려 (그 새 태스크엔 돌아갈 화면이
     * 없으니) 홈 화면으로 튕기는 현상이 있었다 — Activity 컨텍스트에서 호출하는 한 이 플래그는
     * 애초에 필요 없으므로 빼서, 실패하더라도 최소한 우리 앱 화면으로는 돌아오게 한다.
     * resolveActivity로 처리 가능한지 확인하고, 혹시 런타임에도 실패하면(ActivityNotFoundException)
     * 예전처럼 접근성 설정 목록 화면으로 폴백한다.
     */
    fun openAccessibilitySettings(context: Context, serviceClass: Class<*>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // ACTION_ACCESSIBILITY_DETAILS_SETTINGS는 API 34에 추가됐지만 아직 android.provider.Settings에
            // 공개 상수로 노출되지 않아 문자열 리터럴로 직접 써야 한다.
            val detailIntent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, serviceClass))
            if (detailIntent.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(detailIntent) }.onSuccess { return }
            }
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
