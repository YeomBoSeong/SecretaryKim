package com.ybs.assistant.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.Xml
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

private const val TAG = "FileAttachmentUtils"
private const val MAX_EXTRACTED_CHARS = 60_000
private const val MAX_PDF_BYTES = 25L * 1024 * 1024

/**
 * 채팅에 첨부할 수 있는 파일을 처리한 결과.
 * PDF는 원본 바이트를 그대로 API에 보내고(mimeType="application/pdf"), 그 외 텍스트 계열/워드/엑셀은
 * 텍스트만 추출해(extractedText) 메시지 본문에 실어 보낸다 — 어느 provider든 텍스트는 똑같이 이해하므로
 * provider별 바이너리 처리를 신경 쓸 필요가 없다.
 */
data class PreparedFileAttachment(
    val fileUri: Uri?,
    val fileName: String,
    val mimeType: String?,
    val extractedText: String?,
)

object FileAttachmentUtils {

    private val SUPPORTED_EXTENSIONS = setOf("pdf", "txt", "md", "csv", "json", "log", "docx", "xlsx")

    fun isSupportedFileName(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS

    fun prepareChatFileAttachment(context: Context, sourceUri: Uri): PreparedFileAttachment? {
        val name = queryDisplayName(context, sourceUri) ?: sourceUri.lastPathSegment ?: "file"
        val ext = name.substringAfterLast('.', "").lowercase()
        return runCatching {
            when (ext) {
                "pdf" -> preparePdf(context, sourceUri, name)
                "txt", "md", "csv", "json", "log" -> prepareText(context, sourceUri, name)
                "docx" -> prepareDocx(context, sourceUri, name)
                "xlsx" -> prepareXlsx(context, sourceUri, name)
                else -> null
            }
        }.onFailure { Log.e(TAG, "prepareChatFileAttachment failed for $name", it) }.getOrNull()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else {
                null
            }
        }

    private fun truncate(text: String): String =
        if (text.length > MAX_EXTRACTED_CHARS) text.take(MAX_EXTRACTED_CHARS) + "\n...(생략)" else text

    private fun preparePdf(context: Context, sourceUri: Uri, name: String): PreparedFileAttachment? {
        val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_PDF_BYTES) return null
        val dir = File(context.cacheDir, "file_attachments").apply { mkdirs() }
        val file = File(dir, "attach_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { it.write(bytes) }
        return PreparedFileAttachment(Uri.fromFile(file), name, "application/pdf", null)
    }

    private fun prepareText(context: Context, sourceUri: Uri, name: String): PreparedFileAttachment? {
        val bytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() } ?: return null
        return PreparedFileAttachment(null, name, null, truncate(bytes.toString(Charsets.UTF_8)))
    }

    private fun prepareDocx(context: Context, sourceUri: Uri, name: String): PreparedFileAttachment? {
        val xmlBytes = readZipEntry(context, sourceUri, "word/document.xml") ?: return null
        val text = extractDocxText(xmlBytes)
        if (text.isBlank()) return null
        return PreparedFileAttachment(null, name, null, truncate(text))
    }

    private fun prepareXlsx(context: Context, sourceUri: Uri, name: String): PreparedFileAttachment? {
        var sharedStrings: List<String> = emptyList()
        val sheetTexts = sortedMapOf<String, String>()
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    when {
                        entryName == "xl/sharedStrings.xml" -> sharedStrings = extractSharedStrings(zip.readBytes())
                        entryName.startsWith("xl/worksheets/sheet") && entryName.endsWith(".xml") ->
                            sheetTexts[entryName] = String(zip.readBytes(), Charsets.UTF_8)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (sheetTexts.isEmpty()) return null
        val text = sheetTexts.values.joinToString("\n\n") { extractSheetText(it, sharedStrings) }.trim()
        if (text.isBlank()) return null
        return PreparedFileAttachment(null, name, null, truncate(text))
    }

    /** ZIP(워드/엑셀 문서) 안에서 지정한 엔트리 하나만 바이트로 읽는다. */
    private fun readZipEntry(context: Context, sourceUri: Uri, entryPath: String): ByteArray? {
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == entryPath) return zip.readBytes()
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    /** word/document.xml의 <w:t> 텍스트 조각을 문단(<w:p>) 단위 줄바꿈으로 이어 붙인다. */
    private fun extractDocxText(xmlBytes: ByteArray): String {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(xmlBytes.inputStream(), null)
        val sb = StringBuilder()
        var inText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "t" -> inText = true
                        "p" -> sb.append("\n")
                    }
                }
                XmlPullParser.TEXT -> if (inText) sb.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.name == "t") inText = false
            }
            event = parser.next()
        }
        return sb.toString().trim()
    }

    /** sharedStrings.xml의 <si> 순서대로 문자열 목록을 뽑는다 (엑셀 문자열 셀은 이 목록의 인덱스만 저장한다). */
    private fun extractSharedStrings(xmlBytes: ByteArray): List<String> {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(xmlBytes.inputStream(), null)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inText = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> current.clear()
                    "t" -> inText = true
                }
                XmlPullParser.TEXT -> if (inText) current.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "si" -> result += current.toString()
                }
            }
            event = parser.next()
        }
        return result
    }

    /** 시트 xml의 각 행(<row>)을 셀 값들을 탭으로 이어 붙인 한 줄의 텍스트로 바꾼다. */
    private fun extractSheetText(xml: String, sharedStrings: List<String>): String {
        val parser: XmlPullParser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val rows = StringBuilder()
        var row = StringBuilder()
        var cellType: String? = null
        var inValue = false
        val value = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> row = StringBuilder()
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t")
                        value.clear()
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(parser.text)
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val text = if (cellType == "s") {
                            value.toString().trim().toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: ""
                        } else {
                            value.toString()
                        }
                        if (row.isNotEmpty()) row.append("\t")
                        row.append(text)
                    }
                    "row" -> if (row.isNotBlank()) {
                        rows.append(row)
                        rows.append("\n")
                    }
                }
            }
            event = parser.next()
        }
        return rows.toString().trim()
    }
}
