package com.ybs.assistant.agent

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.ybs.assistant.R
import com.ybs.assistant.data.DocumentBuilder
import com.ybs.assistant.data.DocumentEditor
import com.ybs.assistant.data.FileAccessPermission
import com.ybs.assistant.data.FileAttachmentUtils
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

private const val TAG = "FileSystemToolExecutor"
private const val MAX_LIST_ENTRIES = 200

private val WRITABLE_EXTENSIONS = setOf("txt", "md", "csv", "json", "log", "docx", "xlsx")
private val FIND_REPLACE_EXTENSIONS = setOf("txt", "md", "csv", "json", "log", "docx", "xlsx")
private const val CONTENT_PREVIEW_LIMIT = 300

private const val MAX_SEARCH_RESULTS = 50
private const val MAX_SEARCH_ENTRIES_SCANNED = 20_000
private const val MAX_SEARCH_DEPTH = 12
private const val MAX_CONTENT_SEARCH_FILE_BYTES = 2 * 1024 * 1024 // 이보다 큰 파일은 내용 검색에서 건너뛴다(속도 보호)
private val CONTENT_SEARCHABLE_EXTENSIONS = setOf("txt", "md", "csv", "json", "log")
// Android/data, Android/obb는 스코프드 스토리지 정책상 대부분 못 읽고, 읽혀도 앱 내부 캐시성 파일뿐이라
// 사용자가 찾는 파일이 나올 일이 거의 없어 통째로 건너뛴다. 숨김 폴더(.으로 시작)도 마찬가지.
private val SEARCH_SKIP_DIR_NAMES = setOf("Android")

/**
 * 채팅 대화 도중 Claude/GPT/Gemini가 list_files/read_file/write_file/delete_file tool을 호출했을 때
 * 실제로 로컬 파일 시스템에 접근하는 공용 로직. MANAGE_EXTERNAL_STORAGE 권한이 있어야 동작하며
 * (ScreenActionExecutor와 달리 접근성 서비스와는 무관하다), 사용자가 매번 첨부하지 않아도 AI가 스스로
 * 파일을 찾아 읽고 쓸 수 있게 해준다. write_file/delete_file은 되돌리기 어려운 작업이라 실행 전에
 * [confirm]으로 사용자 확인을 받는다 — 거부하면 실제 파일에는 손대지 않는다.
 */
object FileSystemToolExecutor {

    suspend fun execute(context: Context, name: String, input: JSONObject, confirm: suspend (String) -> Boolean): String {
        if (!FileAccessPermission.isGranted()) {
            return context.getString(R.string.fs_permission_denied, context.getString(R.string.app_name))
        }
        return runCatching {
            when (name) {
                "list_files" -> listFiles(context, input.optString("path", ""))
                "search_files" -> searchFiles(
                    context,
                    input.optString("path", ""),
                    input.optString("query", ""),
                    input.optBoolean("search_content", false),
                )
                "read_file" -> readFile(context, input.optString("path", ""))
                "write_file" -> writeFile(context, input.optString("path", ""), input.optString("content", ""), confirm)
                "find_replace_file" -> findReplaceFile(
                    context,
                    input.optString("path", ""),
                    input.optString("find", ""),
                    input.optString("replace", ""),
                    confirm,
                )
                "delete_file" -> deleteFile(context, input.optString("path", ""), confirm)
                "zip_files" -> zipFiles(
                    context,
                    (0 until (input.optJSONArray("paths")?.length() ?: 0)).map { input.optJSONArray("paths")!!.optString(it) },
                    input.optString("output_path", ""),
                    confirm,
                )
                "unzip_file" -> unzipFile(context, input.optString("path", ""), input.optString("output_dir", ""), confirm)
                else -> context.getString(R.string.fs_unknown_tool, name)
            }
        }.onFailure { Log.e(TAG, "execute($name) failed", it) }
            .getOrElse { context.getString(R.string.fs_error_generic, it.message) }
    }

    private fun resolveFile(path: String): File {
        val trimmed = path.trim()
        val file = File(trimmed)
        return if (trimmed.isBlank()) {
            Environment.getExternalStorageDirectory()
        } else if (file.isAbsolute) {
            file
        } else {
            File(Environment.getExternalStorageDirectory(), trimmed)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "${bytes}B"
    }

    private val modifiedDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    /**
     * 파일 시스템은 "다운로드된 시각"을 따로 기록하지 않는다 — 여기 쓰이는 lastModified()는 그 파일의
     * 마지막 수정 시각으로, 다운로드처럼 한 번 쓰고 안 바뀌는 파일이면 사실상 다운로드 시각과 같다.
     */
    private fun listFiles(context: Context, path: String): String {
        val dir = resolveFile(path)
        if (!dir.exists()) return context.getString(R.string.fs_folder_not_found, dir.absolutePath)
        if (!dir.isDirectory) return context.getString(R.string.fs_not_a_folder_use_read, dir.absolutePath)

        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            ?: return context.getString(R.string.fs_folder_unreadable, dir.absolutePath)
        if (entries.isEmpty()) return context.getString(R.string.fs_empty_folder, dir.absolutePath)

        val shown = entries.take(MAX_LIST_ENTRIES)
        val lines = shown.joinToString("\n") { f ->
            val modified = modifiedDateFormat.format(Date(f.lastModified()))
            if (f.isDirectory) {
                context.getString(R.string.fs_entry_dir, f.name, modified)
            } else {
                context.getString(R.string.fs_entry_file, f.name, formatSize(f.length()), modified)
            }
        }
        val suffix = if (entries.size > MAX_LIST_ENTRIES) {
            context.getString(R.string.fs_more_entries, entries.size - MAX_LIST_ENTRIES)
        } else {
            ""
        }
        val note = context.getString(R.string.fs_list_footer_note)
        return "${dir.absolutePath}\n$note\n$lines$suffix"
    }

    /**
     * path 아래를 재귀적으로 훑으면서 파일명(항상)과, search_content가 true면 일반 텍스트 파일의
     * 내용까지 query로 검색한다. list_files와 달리 하위 폴더까지 전부 내려가므로, "이 파일 어디
     * 있는지 모르겠는데 찾아줘" 같은 요청에 쓴다. 저장소가 커도 오래 걸리지 않도록 결과/스캔
     * 개수와 폴더 깊이에 상한을 둔다.
     */
    private fun searchFiles(context: Context, path: String, query: String, searchContent: Boolean): String {
        if (query.isBlank()) return context.getString(R.string.fs_search_query_required)
        val root = resolveFile(path)
        if (!root.exists()) return context.getString(R.string.fs_folder_not_found, root.absolutePath)
        if (!root.isDirectory) return context.getString(R.string.fs_not_a_folder_use_read, root.absolutePath)

        val queryLower = query.lowercase()
        val results = mutableListOf<File>()
        var scanned = 0

        fun matchesContent(file: File): Boolean {
            val ext = file.name.substringAfterLast('.', "").lowercase()
            if (ext !in CONTENT_SEARCHABLE_EXTENSIONS) return false
            if (file.length() !in 1..MAX_CONTENT_SEARCH_FILE_BYTES) return false
            return runCatching { file.readText().contains(query, ignoreCase = true) }.getOrDefault(false)
        }

        fun walk(dir: File, depth: Int) {
            if (results.size >= MAX_SEARCH_RESULTS || scanned >= MAX_SEARCH_ENTRIES_SCANNED || depth > MAX_SEARCH_DEPTH) return
            val entries = runCatching { dir.listFiles() }.getOrNull() ?: return
            for (entry in entries) {
                if (results.size >= MAX_SEARCH_RESULTS || scanned >= MAX_SEARCH_ENTRIES_SCANNED) return
                scanned++
                if (entry.isDirectory) {
                    if (entry.name.startsWith(".") || entry.name in SEARCH_SKIP_DIR_NAMES) continue
                    walk(entry, depth + 1)
                } else if (entry.name.contains(query, ignoreCase = true) || (searchContent && matchesContent(entry))) {
                    results.add(entry)
                }
            }
        }
        walk(root, 0)

        if (results.isEmpty()) return context.getString(R.string.fs_search_no_results, query, root.absolutePath)

        val lines = results.joinToString("\n") { f ->
            val modified = modifiedDateFormat.format(Date(f.lastModified()))
            context.getString(R.string.fs_entry_file, f.absolutePath, formatSize(f.length()), modified)
        }
        val suffix = if (results.size >= MAX_SEARCH_RESULTS) context.getString(R.string.fs_search_truncated, MAX_SEARCH_RESULTS) else ""
        val header = context.getString(R.string.fs_search_result_header, query, root.absolutePath)
        return "$header\n$lines$suffix"
    }

    private fun readFile(context: Context, path: String): String {
        if (path.isBlank()) return context.getString(R.string.fs_read_path_required)
        val file = resolveFile(path)
        if (!file.exists()) return context.getString(R.string.fs_file_not_found, file.absolutePath)
        if (file.isDirectory) return context.getString(R.string.fs_target_is_folder, file.absolutePath)

        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext == "pdf") return context.getString(R.string.fs_pdf_unsupported)
        if (ext in setOf("jpg", "jpeg", "png", "webp")) {
            return context.getString(R.string.fs_image_unsupported)
        }

        val prepared = FileAttachmentUtils.prepareChatFileAttachment(context, Uri.fromFile(file))
            ?: return context.getString(R.string.fs_read_unsupported_format)
        return prepared.extractedText ?: context.getString(R.string.fs_read_failed, file.absolutePath)
    }

    private suspend fun writeFile(context: Context, path: String, content: String, confirm: suspend (String) -> Boolean): String {
        if (path.isBlank()) return context.getString(R.string.fs_write_path_required)
        val file = resolveFile(path)
        if (file.isDirectory) return context.getString(R.string.fs_target_is_folder, file.absolutePath)
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext !in WRITABLE_EXTENSIONS) {
            return context.getString(R.string.fs_write_unsupported_format)
        }

        val existed = file.exists()
        val action = context.getString(if (existed) R.string.fs_action_overwrite else R.string.fs_action_create)
        val preview = if (content.length > CONTENT_PREVIEW_LIMIT) content.take(CONTENT_PREVIEW_LIMIT) + "…(생략)" else content
        val message = context.getString(R.string.fs_write_confirm_message, action, file.absolutePath, preview)
        if (!confirm(message)) return context.getString(R.string.fs_write_declined)

        return runCatching {
            file.parentFile?.mkdirs()
            when (ext) {
                "docx" -> file.writeBytes(DocumentBuilder.buildDocx(content))
                "xlsx" -> file.writeBytes(DocumentBuilder.buildXlsx(content))
                else -> file.writeText(content)
            }
            if (existed) {
                context.getString(R.string.fs_write_overwritten, file.absolutePath)
            } else {
                context.getString(R.string.fs_write_created, file.absolutePath)
            }
        }.getOrElse { context.getString(R.string.fs_write_failed, it.message) }
    }

    /**
     * write_file과 달리 파일을 통째로 새로 만들지 않고, 기존 파일 안에서 [find]를 찾아 [replace]로만
     * 바꾼다. docx/xlsx는 DocumentEditor가 텍스트 조각(run)만 건드리고 서식/스타일/이미지 등 나머지
     * 구조는 그대로 보존한다 — 대신 찾는 문자열이 서로 다른 서식 조각에 걸쳐 나뉘어 있으면 못 찾는다.
     */
    private suspend fun findReplaceFile(
        context: Context,
        path: String,
        find: String,
        replace: String,
        confirm: suspend (String) -> Boolean,
    ): String {
        if (path.isBlank()) return context.getString(R.string.fs_edit_path_required)
        if (find.isEmpty()) return context.getString(R.string.fs_edit_find_required)
        val file = resolveFile(path)
        if (!file.exists()) return context.getString(R.string.fs_file_not_found, file.absolutePath)
        if (file.isDirectory) return context.getString(R.string.fs_target_is_folder, file.absolutePath)
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext !in FIND_REPLACE_EXTENSIONS) {
            return context.getString(R.string.fs_edit_unsupported_format)
        }

        val original = file.readBytes()
        val result = when (ext) {
            "docx" -> DocumentEditor.findReplaceDocx(original, find, replace)
            "xlsx" -> DocumentEditor.findReplaceXlsx(original, find, replace)
            else -> {
                val text = original.toString(Charsets.UTF_8)
                val occurrences = countOccurrences(text, find)
                if (occurrences == 0) null else DocumentEditor.EditResult(occurrences, text.replace(find, replace).toByteArray(Charsets.UTF_8))
            }
        }

        if (result == null || result.matchCount == 0) {
            return context.getString(R.string.fs_edit_not_found, find)
        }

        val preview = if (replace.length > CONTENT_PREVIEW_LIMIT) replace.take(CONTENT_PREVIEW_LIMIT) + "…(생략)" else replace
        val message = context.getString(R.string.fs_edit_confirm_message, find, preview, result.matchCount, file.absolutePath)
        if (!confirm(message)) return context.getString(R.string.fs_edit_declined)

        return runCatching {
            file.writeBytes(result.bytes)
            context.getString(R.string.fs_edit_success, result.matchCount, file.absolutePath)
        }.getOrElse { context.getString(R.string.fs_edit_failed, it.message) }
    }

    private fun countOccurrences(text: String, find: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(find, index)
            if (index < 0) break
            count++
            index += find.length
        }
        return count
    }

    private suspend fun deleteFile(context: Context, path: String, confirm: suspend (String) -> Boolean): String {
        if (path.isBlank()) return context.getString(R.string.fs_delete_path_required)
        val file = resolveFile(path)
        if (!file.exists()) return context.getString(R.string.fs_file_not_found, file.absolutePath)
        if (file.isDirectory) {
            return context.getString(R.string.fs_delete_target_is_folder, file.absolutePath)
        }

        val message = context.getString(R.string.fs_delete_confirm_message, file.absolutePath)
        if (!confirm(message)) return context.getString(R.string.fs_delete_declined)

        return if (file.delete()) {
            context.getString(R.string.fs_delete_success, file.absolutePath)
        } else {
            context.getString(R.string.fs_delete_failed, file.absolutePath)
        }
    }

    private suspend fun zipFiles(
        context: Context,
        paths: List<String>,
        outputPath: String,
        confirm: suspend (String) -> Boolean,
    ): String {
        if (paths.isEmpty()) return context.getString(R.string.fs_zip_paths_required)
        if (outputPath.isBlank()) return context.getString(R.string.fs_zip_output_required)
        val sources = paths.map { resolveFile(it) }
        val missing = sources.firstOrNull { !it.exists() }
        if (missing != null) return context.getString(R.string.fs_file_not_found, missing.absolutePath)

        val resolvedOutput = resolveFile(outputPath)
        val outputFile = if (resolvedOutput.extension.lowercase() == "zip") resolvedOutput else File(resolvedOutput.path + ".zip")

        val message = context.getString(R.string.fs_zip_confirm_message, sources.size, outputFile.absolutePath)
        if (!confirm(message)) return context.getString(R.string.fs_zip_declined)

        return runCatching {
            outputFile.parentFile?.mkdirs()
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zipOut ->
                for (source in sources) addToZip(zipOut, source, source.name)
            }
            context.getString(R.string.fs_zip_success, outputFile.absolutePath)
        }.getOrElse { context.getString(R.string.fs_zip_failed, it.message) }
    }

    private fun addToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return
            for (child in children) addToZip(zipOut, child, "$entryName/${child.name}")
        } else {
            zipOut.putNextEntry(ZipEntry(entryName))
            BufferedInputStream(FileInputStream(file)).use { it.copyTo(zipOut) }
            zipOut.closeEntry()
        }
    }

    private suspend fun unzipFile(
        context: Context,
        path: String,
        outputDir: String,
        confirm: suspend (String) -> Boolean,
    ): String {
        if (path.isBlank()) return context.getString(R.string.fs_unzip_path_required)
        val zipFile = resolveFile(path)
        if (!zipFile.exists()) return context.getString(R.string.fs_file_not_found, zipFile.absolutePath)
        val targetDir = if (outputDir.isBlank()) File(zipFile.parentFile, zipFile.nameWithoutExtension) else resolveFile(outputDir)

        val message = context.getString(R.string.fs_unzip_confirm_message, zipFile.absolutePath, targetDir.absolutePath)
        if (!confirm(message)) return context.getString(R.string.fs_unzip_declined)

        return runCatching {
            targetDir.mkdirs()
            var count = 0
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    // zip slip 방지 — 압축 안의 경로가 targetDir 밖으로 못 나가게 막는다.
                    if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(outFile)).use { zipIn.copyTo(it) }
                        count++
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            context.getString(R.string.fs_unzip_success, count, targetDir.absolutePath)
        }.getOrElse { context.getString(R.string.fs_unzip_failed, it.message) }
    }
}
