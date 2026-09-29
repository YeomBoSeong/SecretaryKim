package com.ybs.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Claude Desktop의 CLAUDE.md처럼, 모든 대화에 항상 포함되는 사용자 지정 고정 프롬프트. */
    fun getSystemPrompt(): String =
        prefs.getString("custom_system_prompt", "") ?: ""

    fun setSystemPrompt(prompt: String) {
        prefs.edit().putString("custom_system_prompt", prompt).apply()
    }

    /** AI 답변이 도착하면 자동으로 음성으로 읽어줄지 여부. 기본은 꺼짐(opt-in) — 재생 버튼은 이 설정과 무관하게 항상 쓸 수 있다. */
    fun isAutoSpeakEnabled(): Boolean = prefs.getBoolean("auto_speak_enabled", false)

    fun setAutoSpeakEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_speak_enabled", enabled).apply()
    }

    /** BYOK(자기 키 지참) — 사용자가 직접 발급받은 Claude API 키. 기기 밖으로 나가지 않고
     * 이 암호화 저장소에만 남아, 앱은 이 키로 Anthropic API를 직접 호출한다. */
    fun getClaudeApiKey(): String? = prefs.getString("claude_api_key", null)?.takeIf { it.isNotBlank() }

    fun setClaudeApiKey(key: String) {
        prefs.edit().putString("claude_api_key", key.trim()).apply()
    }

    fun hasClaudeApiKey(): Boolean = !getClaudeApiKey().isNullOrBlank()

    /** 온보딩(권한→구독)을 한 번이라도 끝까지 통과했는지 — 건너뛰기로 끝냈어도 true.
     * true가 되면 앱 재실행 시 온보딩 화면 대신 곧바로 채팅으로 간다(권한 안내는 채팅 화면 배너가 대신 처리). */
    fun isOnboardingDone(): Boolean = prefs.getBoolean("onboarding_done", false)

    fun setOnboardingDone(done: Boolean) {
        prefs.edit().putBoolean("onboarding_done", done).apply()
    }
}
