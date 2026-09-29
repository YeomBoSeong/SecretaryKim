package com.ybs.assistant.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.ybs.assistant.MainActivity
import com.ybs.assistant.R

/**
 * 화면 자동화 작업이 끝났을 때, 앱이 백그라운드 상태라면 알림을 띄워 사용자가
 * 탭해서 바로 앱으로 돌아올 수 있게 한다.
 *
 * (이전에는 SYSTEM_ALERT_WINDOW 오버레이 버블로 구현했으나, 포그라운드 서비스가 없는
 * 백그라운드 프로세스가 띄우는 TYPE_APPLICATION_OVERLAY 창은 OS가
 * mIsForceHiddenNonSystemOverlayWindow 플래그로 강제로 숨겨 화면에 그려지지 않는 것을
 * 실기기에서 확인했다. addView/removeView 호출 자체는 성공하지만 실제로는 보이지 않는
 * OS 정책이라 일반 알림 방식으로 교체했다.)
 */
object CompletionNotificationManager {
    // 이전 채널(task_completion)은 IMPORTANCE_DEFAULT로 생성돼 있어, 속성을 바꿔도
    // 이미 생성된 채널에는 소급 적용되지 않는다 (안드로이드 정책). 헤드업(상단 팝업)이
    // 뜨려면 IMPORTANCE_HIGH로 새로 만들어야 하므로 채널 ID를 바꿔 재생성한다.
    private const val CHANNEL_ID = "task_completion_v2"
    private const val NOTIFICATION_ID = 1
    private const val ICON_TEXT = "김"

    /**
     * 호출부(ChatViewModel)에서 앱이 백그라운드 상태일 때만 부르도록 이미 걸러서 호출한다.
     * IMPORTANCE_HIGH 채널의 알림은 카카오톡 메시지 알림처럼 화면 위쪽에
     * 잠깐 떴다가 사라지는 헤드업(peek) 형태로 표시된다.
     */
    fun show(context: Context) {
        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(kimIcon())
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS 권한이 없는 경우: 조용히 무시 (배너로 안내됨)
        }
    }

    /** 상태 표시줄에 "김" 글자 모양으로 보이는 알림 아이콘을 그려서 만든다. */
    private fun kimIcon(): IconCompat {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = size * 0.78f
            typeface = Typeface.DEFAULT_BOLD
        }
        val yPos = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(ICON_TEXT, size / 2f, yPos, paint)
        return IconCompat.createWithBitmap(bitmap)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
            manager.createNotificationChannel(channel)
        }
    }
}
