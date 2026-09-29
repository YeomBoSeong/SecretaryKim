package com.ybs.assistant.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * "모든 파일에 대한 액세스"(MANAGE_EXTERNAL_STORAGE) 권한. 접근성 권한과 마찬가지로 앱이 스스로
 * 켤 수 없고, 전용 시스템 설정 화면으로 사용자를 보내야 한다.
 */
object FileAccessPermission {
    fun isGranted(): Boolean = Environment.isExternalStorageManager()

    fun openSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
