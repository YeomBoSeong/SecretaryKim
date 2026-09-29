package com.ybs.assistant.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ImageUtils"

/** 채팅 첨부 이미지는 이 변 길이를 넘지 않게 줄인다 (Claude Vision 권장 최대 변 길이와 맞춤 — 이보다 크면 API가 어차피 리사이즈하므로 업로드 용량만 아낀다). */
private const val CHAT_ATTACHMENT_MAX_DIMENSION = 1568

object ImageUtils {

    /** 카메라 원본 해상도는 OOM을 유발할 수 있어 필요한 크기로 다운샘플링하고, EXIF 방향에 맞춰 회전한다. */
    fun decodeScaledBitmap(context: Context, uri: Uri, maxDimension: Int = 2000): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // inJustDecodeBounds=true 모드에서는 decodeStream이 항상 null을 반환하는 게 정상이라(옵션에 크기만 채워준다),
        // 스트림 자체를 열 수 있었는지로만 실패 여부를 판단해야 한다.
        val boundsStream = resolver.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sampleSize = 1
        val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
        while (largestDimension / sampleSize > maxDimension) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        // 카메라로 찍은 사진은 EXIF 방향 태그만 세로로 표시하고 실제 픽셀은 눕혀서 저장되는 경우가 많아,
        // 회전을 적용하지 않으면 기울어진 상태로 보이거나 인식되지 않는다.
        val exifOrientation = resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val rotationDegrees = when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        Log.d(
            TAG,
            "decodeScaledBitmap: original=${bounds.outWidth}x${bounds.outHeight} sampleSize=$sampleSize " +
                "decoded=${bitmap.width}x${bitmap.height} exifOrientation=$exifOrientation rotation=$rotationDegrees",
        )

        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * 카메라/갤러리에서 고른 원본 이미지를 채팅 전송에 적합한 크기로 줄이고 회전을 바로잡아
     * 앱 캐시에 JPEG로 저장한 뒤 그 파일의 Uri를 반환한다. 실패하면 null.
     */
    fun prepareChatAttachment(context: Context, sourceUri: Uri): Uri? {
        val bitmap = runCatching { decodeScaledBitmap(context, sourceUri, CHAT_ATTACHMENT_MAX_DIMENSION) }
            .onFailure { Log.e(TAG, "prepareChatAttachment: decode failed", it) }
            .getOrNull() ?: return null

        val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val file = File(dir, "chat_${System.currentTimeMillis()}.jpg")
        return runCatching {
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            Uri.fromFile(file)
        }.onFailure { Log.e(TAG, "prepareChatAttachment: save failed", it) }.getOrNull()
    }
}
