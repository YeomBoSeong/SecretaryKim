package com.ybs.assistant.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LatestVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
)

/** 플레이스토어 없이 개인 배포되는 앱이라, 자체 서버(app/api/app-version)에 최신 버전을 물어봐서
 * 업데이트 여부를 판단한다. 실패해도 조용히 무시한다 — 업데이트 확인은 부가 기능이다. */
object UpdateChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun fetchLatestVersion(): LatestVersionInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(BackendConfig.APP_VERSION_URL).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                LatestVersionInfo(
                    versionCode = json.optInt("versionCode", -1),
                    versionName = json.optString("versionName"),
                    downloadUrl = json.optString("downloadUrl"),
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
