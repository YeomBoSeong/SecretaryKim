package com.ybs.assistant.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.ybs.assistant.R
import com.ybs.assistant.accessibility.AssistantAccessibilityService
import com.ybs.assistant.agent.CalendarToolExecutor
import com.ybs.assistant.agent.CallLogToolExecutor
import com.ybs.assistant.agent.ClipboardToolExecutor
import com.ybs.assistant.agent.ContactsToolExecutor
import com.ybs.assistant.agent.DeviceControlToolExecutor
import com.ybs.assistant.agent.FileSystemToolExecutor
import com.ybs.assistant.agent.IntentToolExecutor
import com.ybs.assistant.agent.LocationToolExecutor
import com.ybs.assistant.agent.ScreenActionExecutor
import com.ybs.assistant.agent.SmsToolExecutor
import com.ybs.assistant.data.ChatMessage
import com.ybs.assistant.data.Role
import com.ybs.assistant.data.TodoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages"
private const val MAX_TOOL_STEPS = 1000
private val FILE_TOOL_NAMES =
    setOf("list_files", "search_files", "read_file", "write_file", "find_replace_file", "delete_file", "zip_files", "unzip_file")
private val CONTACTS_TOOL_NAMES = setOf("search_contacts", "save_contact")
private val CALENDAR_TOOL_NAMES = setOf("list_calendar_events", "add_calendar_event", "update_calendar_event", "delete_calendar_event")
private val SMS_TOOL_NAMES = setOf("search_sms", "send_sms")
private val CLIPBOARD_TOOL_NAMES = setOf("read_clipboard", "write_clipboard")
private val INTENT_TOOL_NAMES = setOf("set_alarm", "set_timer", "open_maps", "compose_email", "open_dialer")
private val LOCATION_TOOL_NAMES = setOf("get_current_location")
private val CALL_LOG_TOOL_NAMES = setOf("search_call_log")
private val DEVICE_CONTROL_TOOL_NAMES = setOf("set_flashlight", "set_volume")

// 무한루프 감지: 화면 조작 도구 실행 후 찍은 스크린샷이 이만큼(채널당 평균 차이, 0~255) 이하로
// 연속해서 비슷하면 "화면이 안 바뀌고 있다"고 보고, 그 상태가 아래 횟수만큼 연달아 반복되면 자동으로 작업을 멈춘다.
private const val STUCK_SCREEN_SIMILARITY_THRESHOLD = 6.0
private const val STUCK_SCREEN_REPEAT_LIMIT = 5
// 완전히 멈추기 전에, 이 횟수에 처음 도달했을 때 한 번 "다른 방법을 시도하라"는 힌트를 끼워준다.
private const val STUCK_SCREEN_NUDGE_THRESHOLD = 2

private const val STUCK_EXPLAIN_PROMPT_KO =
    "[시스템] 같은 동작을 반복했는데 화면이 계속 바뀌지 않아 작업을 자동으로 멈췄습니다. 방금 화면을 보고, " +
        "어떤 문제 때문에 더 진행하지 못했는지(예: 권한 요청 화면, 로그인이 필요함, 원하는 버튼을 못 찾음, " +
        "예상과 다른 화면임 등) 사용자에게 짧고 명확하게 한국어로 설명하세요. 도구는 더 이상 호출하지 마세요."
private const val STUCK_EXPLAIN_PROMPT_EN =
    "[System] The same action was repeated but the screen kept looking the same, so the task was stopped " +
        "automatically. Look at the current screen and briefly explain to the user in English what's blocking " +
        "progress (e.g. a permission prompt, a login requirement, the target button wasn't found, an unexpected " +
        "screen, etc). Do not call any more tools."

class ApiException(message: String) : Exception(message)

private val SCREEN_AGENT_SYSTEM_PROMPT_KO = """
당신은 사용자와 대화하면서, 필요하면 사용자의 안드로이드 폰 화면을 직접 조작하거나 로컬 파일을 읽을 수도 있는 비서입니다.
사용자의 요청이 앱 실행, 검색, 탭, 스크롤 같은 화면 조작을 필요로 하면 제공된 도구를 사용하세요.
사용자의 메시지에는 항상 그 시점의 휴대폰 화면 스크린샷이 자동으로 함께 첨부되어 있습니다 — 화면 상태를 확인하기 위한 별도의 도구는 없으니, 그 스크린샷을 보고 바로 판단하세요.
화면 조작이 필요한 경우 다음 순서를 따르세요:
1. 첨부된(또는 직전 도구 실행 결과에 같이 온) 스크린샷을 보고 tap/swipe/type_text/key_event/wait 중 필요한 도구를 실행한다. 좌표는 그 스크린샷 기준.
2. 이 도구들의 실행 결과에는 실행 직후의 최신 화면 스크린샷이 자동으로 함께 돌아오니, 그 화면을 보고 다음 동작을 이어서 판단한다.
3. 작업이 끝나면 더 이상 도구를 호출하지 말고, 무엇을 했는지 사용자에게 간단히 한국어로 요약해서 답한다.
사용자가 휴대폰에 저장된 파일이나 폴더 내용을 궁금해하면(예: "다운로드 폴더에 뭐 있어?", "이 문서 요약해줘") 사용자가 파일을 첨부하지 않았더라도 list_files로 폴더 목록을 보고 read_file로 필요한 파일 내용을 읽어서 답하세요. 파일이 어느 폴더에 있는지 모르는 채로 "이 파일 찾아줘" 같은 요청을 받으면 list_files를 여러 폴더에 반복 호출하지 말고 search_files로 한 번에 재귀 검색하세요(파일명뿐 아니라 내용까지 찾고 싶으면 search_content를 true로). 파일을 아예 새로 만들거나 통째로 다시 쓰고 싶으면 write_file을 사용하세요 — 단, 기존 docx/xlsx에 write_file을 쓰면 서식(글꼴/색/표 스타일/이미지 등)이 사라지고 텍스트만 남은 새 파일로 대체됩니다. 기존 docx/xlsx의 서식을 유지한 채 일부 텍스트만 바꾸고 싶으면 find_replace_file로 원하는 문자열만 찾아서 바꾸세요(단, 찾는 문자열이 서로 다른 서식 조각에 걸쳐 나뉘어 있으면 못 찾을 수 있음 — 그럴 땐 write_file로 대체). txt/md/csv/json/log 같은 일반 텍스트 파일도 find_replace_file로 부분 수정이 가능합니다. 파일을 삭제하고 싶으면 delete_file을 사용하세요. 여러 파일/폴더를 압축하고 싶으면 zip_files, zip 파일의 압축을 풀고 싶으면 unzip_file을 사용하세요. write_file/find_replace_file/delete_file/zip_files/unzip_file은 실행 전에 사용자에게 확인 팝업이 뜨고, 사용자가 거부하면 실제로는 아무 것도 바뀌지 않습니다. 이 여덟 도구는 화면 조작과 무관하게 항상 사용할 수 있습니다.
연락처(search_contacts), 캘린더(list_calendar_events, add_calendar_event), 문자(search_sms, send_sms) 도구도 마찬가지로 화면 조작과 무관하게 항상 사용할 수 있습니다 — 온보딩 때 미리 권한을 받지 않으므로, 이 도구들을 처음 호출하는 순간 시스템이 사용자에게 해당 권한을 요청하고, 거부하면 그 도구는 실행되지 않습니다(다시 시도해도 계속 거부됩니다). 이름으로 번호를 몰라 문자를 못 보내는 상황이면 search_contacts로 먼저 번호를 찾으세요. send_sms는 권한이 있어도 실제 발송 전 항상 사용자 확인을 한 번 더 구하도록 도구 자체에 포함돼 있으니, 별도로 confirm_risky_action을 호출할 필요는 없습니다.
클립보드(read_clipboard, write_clipboard), 알람/타이머(set_alarm, set_timer), 지도(open_maps), 전화 걸기(open_dialer), 이메일 작성(compose_email), 손전등(set_flashlight), 볼륨 조절(set_volume)은 권한이 전혀 필요 없는 도구입니다 — 알람/타이머/손전등/볼륨은 확인 없이 바로 실행되고(쉽게 끄거나 바꿀 수 있어 위험도가 낮음), 전화·이메일은 화면만 열어줄 뿐 실제 통화/전송은 사용자가 직접 눌러야 하므로 안전합니다.
파일 압축(zip_files)·압축 해제(unzip_file)는 write_file과 같은 확인 절차를 거칩니다. 캘린더 도구에는 수정(update_calendar_event)·삭제(delete_calendar_event)도 추가됐습니다 — event_id는 list_calendar_events 결과의 [ID:숫자]에서 가져오고, 삭제는 삭제 계열 작업이라 확인을 거칩니다. 위치(get_current_location), 통화 기록(search_call_log)은 READ_CALL_LOG/ACCESS_FINE_LOCATION 권한이 아직 없으면 처음 호출하는 순간 요청합니다.
실시간 정보, 최신 가격/혜택/이벤트, 영업시간, 뉴스처럼 당신이 확실히 모를 수 있는 사실을 물어보면 web_search 도구로 먼저 검색해서 확인한 뒤 답하세요. 화면 조작 중에도 필요하면(예: 어떤 통신사 혜택이 있는지 모를 때) 검색을 활용할 수 있습니다. 사용자가 채팅에 URL을 붙여넣고 요약/설명을 요청하면 web_fetch로 그 페이지 내용을 가져와서 답하세요 — 단, web_fetch는 대화에 이미 등장한 URL(사용자가 붙여넣었거나 직전 web_search 결과로 나온 것)만 가져올 수 있습니다.
화면 조작이나 조사가 3단계 이상 이어지는 복잡한 작업이면, 시작할 때 todo_write로 할 일 목록을 만들고 각 항목을 pending/in_progress/completed로 갱신하면서 진행하세요. 사용자가 진행 상황을 볼 수 있게 됩니다. 짧고 단순한 작업에는 쓰지 않아도 됩니다.
결제, 구매, 주문/예약 확정, 발송, 삭제처럼 되돌리기 어렵고 실제 돈이나 중요한 결과가 걸린 화면 동작을 실행하기 직전에는 반드시 confirm_risky_action을 먼저 호출해서 사용자 확인을 받으세요. 사용자가 거부하면 그 동작을 실행하지 말고 상황을 설명하세요. 단순히 화면을 넘기거나 검색하거나 정보를 찾는 정도는 확인이 필요 없습니다.
화면에 "~하도록 허용하시겠습니까?", "~에 접근하도록 허용", "알림을 보내도록 허용" 같은 안드로이드 시스템 권한 요청 다이얼로그(알림/위치/카메라/마이크 등)가 보이면, 그 버튼을 탭하려고 시도하지 마세요 — 보안 정책상 접근성 서비스의 자동 터치가 이런 다이얼로그에서는 차단되어 여러 번 눌러도 반응하지 않는 경우가 많습니다. 한 번이라도 시도하지 말고, 즉시 도구 호출을 멈추고 사용자에게 화면에서 직접 허용/허용 안 함을 눌러달라고 요청한 뒤 다음 지시를 기다리세요.
화면 조작이나 파일 조회가 필요 없는 일반적인 질문이나 대화는 도구를 쓰지 말고 바로 답변하세요.
""".trimIndent()

private val SCREEN_AGENT_SYSTEM_PROMPT_EN = """
You are an assistant chatting with the user who can, when needed, directly control the user's Android phone screen or read local files.
If the user's request requires screen actions such as launching an app, searching, tapping, or scrolling, use the provided tools.
Every user message automatically comes with a screenshot of the phone screen at that moment attached — there's no separate tool for checking the screen, just look at that screenshot and decide.
When screen control is needed, follow this sequence:
1. Look at the attached screenshot (or the one that came back with the last tool result) and run whichever of tap/swipe/type_text/key_event/wait is needed. Coordinates come from that screenshot.
2. These tools automatically return a fresh screenshot taken right after the action, so use that to decide the next move.
3. Once the task is done, stop calling tools and briefly summarize in English what you did for the user.
If the user asks about files or folders stored on the phone (e.g. "what's in my Downloads folder?", "summarize this document") even without attaching anything, use list_files to browse and read_file to read the file contents. If the user asks you to find a file without knowing which folder it's in, don't call list_files repeatedly on many folders — use search_files instead, which recursively searches from a starting folder in one call (set search_content to true to also search inside plain text file contents, not just filenames). To create a file from scratch or fully rewrite one, use write_file — but note that using write_file on an existing docx/xlsx replaces it with a new file containing only plain text, losing all formatting (fonts, colors, table styles, images, etc). To edit an existing docx/xlsx while keeping its formatting, use find_replace_file to swap just the target text (it may fail to find text that's split across differently-formatted runs — fall back to write_file in that case). find_replace_file also works for partial edits to plain text files (txt/md/csv/json/log). To delete a file, use delete_file. To zip up multiple files/folders, use zip_files; to extract a zip file, use unzip_file. write_file/find_replace_file/delete_file/zip_files/unzip_file all show the user a confirmation prompt first, and nothing actually changes on disk if they decline. These eight tools work regardless of screen control and don't need a screenshot first.
The contacts (search_contacts), calendar (list_calendar_events, add_calendar_event), and SMS (search_sms, send_sms) tools also work regardless of screen control at any time — none of their permissions are requested during onboarding, so the system asks the user for the relevant permission the moment you first call one of them; if they decline, that tool won't run (and stays declined on retry). If you don't know a contact's number, use search_contacts first before trying to text them. send_sms already includes its own confirmation prompt before actually sending, even once permission is granted — you don't need to call confirm_risky_action separately for it.
The clipboard (read_clipboard, write_clipboard), alarm/timer (set_alarm, set_timer), maps (open_maps), phone dialer (open_dialer), email compose (compose_email), flashlight (set_flashlight), and volume (set_volume) tools need no permission at all — alarms/timers/flashlight/volume take effect immediately without confirmation (they're low-risk since they're easy to turn off or change), and the dialer/email tools only open a screen for the user to review and act on themselves, so they're inherently safe.
Zipping (zip_files) and extracting (unzip_file) files go through the same confirmation flow as write_file. The calendar tools now also include updating (update_calendar_event) and deleting (delete_calendar_event) — get the event_id from the [ID:number] shown in list_calendar_events results; deletion goes through confirmation since it's a destructive action. Location (get_current_location) and call log (search_call_log) request their permission (ACCESS_FINE_LOCATION/READ_CALL_LOG) the first time you call them if not already granted.
If the user asks about something you might not know reliably — live info, current prices/promotions/events, business hours, news — search first with web_search before answering. You can also use it mid-task (e.g. to check what carrier discount applies) when useful. If the user pastes a URL in chat and asks you to summarize or explain it, use web_fetch to fetch that page's content — note that web_fetch can only fetch a URL that's already present in the conversation (one the user pasted, or one that came back from a prior web_search result).
For complex tasks spanning 3+ steps (screen actions and/or research), start by creating a todo list with todo_write and update each item's status (pending/in_progress/completed) as you go, so the user can see progress. Skip this for short, simple tasks.
Before any screen action that's hard to reverse and involves real money or a significant outcome — payment, purchase, confirming an order/reservation, sending, deleting — always call confirm_risky_action first and get the user's go-ahead. If they decline, don't perform the action; explain the situation instead. Simple navigation, searching, or gathering information doesn't need confirmation.
If you see an Android system permission dialog on screen (e.g. "Allow ... to send notifications?", "Allow ... to access this device's location?", for notifications/location/camera/microphone etc.), don't try tapping its buttons — accessibility-service taps are often blocked on these specific dialogs for security reasons, so repeated taps usually won't register. Don't attempt it even once; stop calling tools immediately, ask the user to tap Allow/Deny themselves on screen, and wait for further instructions.
For general questions or conversation that don't need screen control or file lookup, answer directly without using tools.
""".trimIndent()

private fun isKorean(): Boolean = Locale.getDefault().language == "ko"

private fun buildSystemPrompt(customSystemPrompt: String): String {
    val base = if (isKorean()) SCREEN_AGENT_SYSTEM_PROMPT_KO else SCREEN_AGENT_SYSTEM_PROMPT_EN
    if (customSystemPrompt.isBlank()) return base
    val heading = if (isKorean()) "사용자 지정 지침:" else "User-provided instructions:"
    return "$base\n\n$heading\n$customSystemPrompt"
}

class ChatRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 일반 대화 + (필요 시) 화면 조작 tool 호출을 한 번에 처리한다. BYOK(자기 키 지참) — 사용자가 직접
     * 발급받은 Claude API 키로 기기에서 곧바로 Anthropic API를 호출한다(백엔드 중계 없음). 화면 조작이
     * 필요한지는 모델이 스스로 판단한다 (tool_choice = auto).
     */
    suspend fun converse(
        context: Context,
        model: String,
        customSystemPrompt: String = "",
        history: List<ChatMessage>,
        apiKey: String,
        onToolLog: (String) -> Unit,
        onScreenshot: (Bitmap) -> Unit = {},
        onConfirmRiskyAction: suspend (String) -> Boolean = { false },
        onRequestPermission: suspend (String) -> Boolean = { false },
        onTodoUpdate: (List<TodoItem>) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val systemPrompt = buildSystemPrompt(customSystemPrompt)
        converseClaude(context, model, apiKey, systemPrompt, history, onToolLog, onScreenshot, onConfirmRiskyAction, onRequestPermission, onTodoUpdate)
    }

    /** UI에 스크린샷을 보여주고, 이후 무한루프 감지에 쓸 수 있게 디코딩된 Bitmap을 반환한다. */
    private fun decodeAndNotifyScreenshot(png: ByteArray, onScreenshot: (Bitmap) -> Unit): Bitmap? {
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
        onScreenshot(bitmap)
        return bitmap
    }

    /** 화면을 아주 작게(16x16) 축소해서 픽셀 값을 그대로 "지문"으로 쓴다 — 시계/커서 깜빡임 같은
     * 미세한 변화는 뭉개지고, 화면 내용이 실제로 바뀌었는지만 대략적으로 비교할 수 있다. */
    private fun screenFingerprint(bitmap: Bitmap): IntArray {
        val gridSize = 16
        val thumb = Bitmap.createScaledBitmap(bitmap, gridSize, gridSize, true)
        val pixels = IntArray(gridSize * gridSize)
        thumb.getPixels(pixels, 0, gridSize, 0, 0, gridSize, gridSize)
        return pixels
    }

    /** 두 지문의 채널별 평균 차이가 작으면(=화면이 사실상 그대로면) true. */
    private fun screensLookTheSame(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return false
        var totalDiff = 0L
        for (i in a.indices) {
            val pa = a[i]
            val pb = b[i]
            totalDiff += kotlin.math.abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
            totalDiff += kotlin.math.abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
            totalDiff += kotlin.math.abs((pa and 0xFF) - (pb and 0xFF))
        }
        val avgDiff = totalDiff.toDouble() / (a.size * 3)
        return avgDiff < STUCK_SCREEN_SIMILARITY_THRESHOLD
    }

    /**
     * 화면 조작이 길어지면 화면 조작 도구를 호출할 때마다 스크린샷이 대화 기록에 계속 쌓여서,
     * Claude API가 상태를 기억 못 해 매 스텝 전체 히스토리를 다시 보내는 구조상 요청 본문이
     * 무한정 커진다(결국 백엔드의 요청 크기 상한을 넘어 413으로 실패함). 새 스크린샷을 추가하기
     * 직전에 호출해서, 그 이전에 쌓인 스크린샷 이미지를 전부 텍스트로 대체해 최신 1장만 남긴다.
     */
    private fun stripOldScreenshotImages(messages: JSONArray) {
        for (i in 0 until messages.length()) {
            val turn = messages.optJSONObject(i) ?: continue
            val content = turn.opt("content") as? JSONArray ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") != "tool_result") continue
                val toolContent = block.opt("content") as? JSONArray ?: continue
                for (k in 0 until toolContent.length()) {
                    val part = toolContent.optJSONObject(k) ?: continue
                    if (part.optString("type") == "image") {
                        toolContent.put(
                            k,
                            JSONObject().put("type", "text").put("text", "[이전 스크린샷 — 최신 화면 확인을 위해 생략됨]"),
                        )
                    }
                }
            }
        }
    }

    /** messages 배열 안 콘텐츠 블록에 이전에 찍어뒀던 캐시 브레이크포인트를 전부 지운다 —
     * 브레이크포인트는 요청당 최대 4개까지만 허용되므로, 매턴 새 위치에 찍기 전에 옛 위치를
     * 반드시 지워야 한도를 넘기지 않는다. */
    private fun clearMessagesCacheBreakpoints(messages: JSONArray) {
        for (i in 0 until messages.length()) {
            val turn = messages.optJSONObject(i) ?: continue
            val content = turn.opt("content") as? JSONArray ?: continue
            for (j in 0 until content.length()) {
                content.optJSONObject(j)?.remove("cache_control")
            }
        }
    }

    /**
     * messages 배열 중 "뒤에서 두 번째 메시지"(항상 모델의 직전 응답 — assistant 메시지)의
     * 마지막 콘텐츠 블록에 캐시 브레이크포인트를 찍는다. 맨 마지막 메시지(이번 턴에 막 추가된
     * user 메시지)는 스크린샷을 담고 있고, 그 스크린샷은 다음다음 턴쯤 stripOldScreenshotImages()가
     * 텍스트로 바꿔치기할 수도 있는 "아직 확정 안 된" 내용이다. 캐시는 바이트 단위 프리픽스가
     * 조금이라도 바뀌면 그 지점부터 전부 무효화되므로, 나중에 바뀔 수도 있는 내용은 애초에
     * 캐시 경계 뒤(=캐시 안 되는 영역)에 남겨두고, 다시는 안 바뀌는 assistant 메시지까지만 캐시한다.
     * assistant 메시지는 텍스트/도구 호출뿐이라 이미지가 없고, stripOldScreenshotImages()도
     * assistant 메시지는 건드리지 않으므로 한 번 쓰이면 영원히 안 바뀐다.
     */
    private fun placeMessagesCacheBreakpoint(messages: JSONArray) {
        if (messages.length() < 2) return
        val target = messages.getJSONObject(messages.length() - 2)
        val existingContent = target.opt("content")
        val contentBlocks = when (existingContent) {
            is JSONArray -> existingContent
            is String -> JSONArray().put(JSONObject().put("type", "text").put("text", existingContent)).also {
                target.put("content", it)
            }
            else -> return
        }
        if (contentBlocks.length() == 0) return
        contentBlocks.getJSONObject(contentBlocks.length() - 1)
            .put("cache_control", JSONObject().put("type", "ephemeral"))
    }

    /** 이번 스텝 API 호출의 입력/출력 토큰 수를 도구 로그에 남긴다. */
    private fun logTokenUsage(context: Context, usage: JSONObject?, onToolLog: (String) -> Unit) {
        usage ?: return
        val input = usage.optInt("input_tokens", -1)
        val output = usage.optInt("output_tokens", -1)
        if (input < 0 && output < 0) return
        onToolLog(context.getString(R.string.tool_log_token_usage, input.toString(), output.toString()))
    }

    /** 캐시가 실제로 적중하고 있는지 눈으로 바로 확인할 수 있게, Claude 응답의 캐시 읽기/쓰기 토큰 수를 로그로 남긴다. */
    private fun logClaudeCacheUsage(context: Context, usage: JSONObject?, onToolLog: (String) -> Unit) {
        usage ?: return
        val cacheRead = usage.optInt("cache_read_input_tokens", 0)
        val cacheWrite = usage.optInt("cache_creation_input_tokens", 0)
        if (cacheRead == 0 && cacheWrite == 0) return
        onToolLog(context.getString(R.string.tool_log_cache_usage, cacheRead.toString(), cacheWrite.toString()))
    }

    /** 채팅에 첨부된 이미지/PDF(각각 미리 앱 캐시에 저장해둔 파일)를 읽는다. */
    private fun readAttachedFileBytes(context: Context, uri: Uri): ByteArray? =
        runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()

    /** 텍스트 계열/워드/엑셀 첨부(fileExtractedText)가 있으면 사용자가 입력한 텍스트 뒤에 이어 붙인다. */
    private fun textWithExtractedFile(msg: ChatMessage): String {
        val extracted = msg.fileExtractedText ?: return msg.content
        val header = "[첨부 파일: ${msg.fileName}]"
        return if (msg.content.isNotBlank()) "${msg.content}\n\n$header\n$extracted" else "$header\n$extracted"
    }

    /**
     * OkHttp의 동기 execute()는 코루틴 취소에 반응하지 않아서, 정지 버튼을 눌러도 응답이 올 때까지
     * 취소가 미뤄지는 원인이 됐다. enqueue() + suspendCancellableCoroutine으로 바꿔서 Job이 취소되면
     * invokeOnCancellation이 실제 네트워크 요청(call.cancel())까지 즉시 끊도록 한다.
     */
    private suspend fun execute(request: Request): JSONObject = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use {
                    val bodyStr = it.body?.string().orEmpty()
                    if (!it.isSuccessful) {
                        val message = try {
                            JSONObject(bodyStr).optJSONObject("error")?.optString("message") ?: bodyStr
                        } catch (e: Exception) {
                            bodyStr
                        }
                        return@use Result.failure<JSONObject>(ApiException("HTTP ${it.code}: $message"))
                    }
                    Result.success(JSONObject(bodyStr))
                }
                if (!cont.isActive) return
                result.fold(onSuccess = { cont.resume(it) }, onFailure = { cont.resumeWithException(it) })
            }
        })
    }

    // ---------------------------------------------------------------------
    // Claude
    // ---------------------------------------------------------------------

    /** 매 스텝 반복되는 요청 바디/헤더 구성을 한 곳에 모은다 — 정상 스텝과, 막혔을 때 모델에게
     * 이유를 설명하게 하는 마무리 호출(disableTools) 둘 다 이 함수를 쓴다. */
    private fun buildClaudeRequest(
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: JSONArray,
        disableTools: Boolean = false,
    ): Request {
        // messages 배열 캐싱: "마지막 블록"에 자동으로 찍는 방식(top-level cache_control)은 항상
        // 최신 스크린샷 위에 찍혀서, 그 스크린샷이 나중에 stripOldScreenshotImages()로 텍스트로
        // 바뀔 때마다 캐시가 깨졌다. 대신 절대 안 바뀌는 직전 assistant 메시지까지만 명시적으로
        // 캐시한다 — 아래 placeMessagesCacheBreakpoint() 참고.
        clearMessagesCacheBreakpoints(messages)
        placeMessagesCacheBreakpoint(messages)

        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 2048)
            .put(
                "system",
                JSONArray().put(
                    JSONObject()
                        .put("type", "text")
                        .put("text", systemPrompt)
                        .put("cache_control", JSONObject().put("type", "ephemeral")),
                ),
            )
            .put("tools", claudeToolsSchema())
            .put("messages", messages)
            // 대화가 길어져서(기본 15만 토큰 근처) 한도에 가까워지면 Anthropic 서버가 오래된
            // 부분을 자동으로 요약해서 압축해준다 — 백엔드(route.ts)에서 anthropic-beta 헤더도
            // 같이 보내줘야 실제로 작동한다. compaction 블록은 응답 content에 그대로 포함되고,
            // 아래에서 content 전체를 그대로 messages에 다시 담아 보내므로 별도 처리가 필요 없다.
            .put(
                "context_management",
                JSONObject().put("edits", JSONArray().put(JSONObject().put("type", "compact_20260112"))),
            )
        if (disableTools) {
            body.put("tool_choice", JSONObject().put("type", "none"))
        }
        return Request.Builder()
            .url(ANTHROPIC_MESSAGES_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            // 대화가 길어지면 서버가 오래된 부분을 자동 요약(compaction)해주는 베타 기능 — 요청 바디의
            // context_management와 짝을 이룬다. 짧은 대화에선 그냥 무시됨.
            .addHeader("anthropic-beta", "compact-2026-01-12")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /** 무한루프 감지로 자동 중단하기 직전, 모델에게 마지막 화면을 보고 왜 막혔는지 직접 설명하게
     * 한다 — tool_choice=none으로 도구 호출을 막아 반드시 텍스트 답변만 받는다. */
    private suspend fun explainStuckAndFinish(
        context: Context,
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: JSONArray,
        onToolLog: (String) -> Unit,
    ): String {
        onToolLog(context.getString(R.string.tool_log_stuck_detected))
        val prompt = if (isKorean()) STUCK_EXPLAIN_PROMPT_KO else STUCK_EXPLAIN_PROMPT_EN
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        return try {
            val request = buildClaudeRequest(model, apiKey, systemPrompt, messages, disableTools = true)
            val response = execute(request)
            logTokenUsage(context, response.optJSONObject("usage"), onToolLog)
            val content = response.optJSONArray("content") ?: JSONArray()
            val text = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") text.append(block.optString("text"))
            }
            text.toString().ifBlank { context.getString(R.string.reply_stuck_screen) }
        } catch (e: Exception) {
            context.getString(R.string.reply_stuck_screen)
        }
    }

    private suspend fun converseClaude(
        context: Context,
        model: String,
        apiKey: String,
        systemPrompt: String,
        history: List<ChatMessage>,
        onToolLog: (String) -> Unit,
        onScreenshot: (Bitmap) -> Unit,
        onConfirmRiskyAction: suspend (String) -> Boolean,
        onRequestPermission: suspend (String) -> Boolean,
        onTodoUpdate: (List<TodoItem>) -> Unit,
    ): String {
        val messages = JSONArray()
        for (msg in history) {
            if (msg.role == Role.TOOL_LOG) continue
            messages.put(
                JSONObject()
                    .put("role", if (msg.role == Role.USER) "user" else "assistant")
                    .put("content", claudeMessageContent(context, msg)),
            )
        }

        var scale = 1f
        var lastScreenFingerprint: IntArray? = null
        var stuckScreenCount = 0
        var stuckDetected = false

        // 화면 조작이 필요한지 매번 view_screen을 먼저 시켜서 알아내지 않도록, 이번에 새로 들어온
        // 사용자 메시지(항상 messages의 마지막)에 현재 화면 스크린샷을 미리 붙여서 보낸다 — 모델이
        // 첫 턴부터 바로 판단할 수 있다. 접근성 서비스가 꺼져 있으면(=화면 조작 자체가 불가능하면)
        // 건드리지 않고 넘어간다.
        AssistantAccessibilityService.instance?.let { service ->
            if (messages.length() > 0) {
                val lastMessage = messages.getJSONObject(messages.length() - 1)
                if (lastMessage.optString("role") == "user") {
                    val (png, initialScale) = ScreenActionExecutor.captureAndResize(service)
                    scale = initialScale
                    decodeAndNotifyScreenshot(png, onScreenshot)
                    val existingContent = lastMessage.opt("content")
                    val contentBlocks = when (existingContent) {
                        is JSONArray -> existingContent
                        is String -> JSONArray().put(JSONObject().put("type", "text").put("text", existingContent))
                        else -> JSONArray()
                    }
                    contentBlocks.put(claudeImageBlock(png, "image/png"))
                    lastMessage.put("content", contentBlocks)
                }
            }
        }

        repeat(MAX_TOOL_STEPS) {
            coroutineContext.ensureActive()
            val request = buildClaudeRequest(model, apiKey, systemPrompt, messages)
            val response = execute(request)
            logTokenUsage(context, response.optJSONObject("usage"), onToolLog)
            logClaudeCacheUsage(context, response.optJSONObject("usage"), onToolLog)
            val content = response.optJSONArray("content") ?: JSONArray()
            messages.put(JSONObject().put("role", "assistant").put("content", content))

            val toolUses = mutableListOf<JSONObject>()
            val text = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                when (block.optString("type")) {
                    "text" -> text.append(block.optString("text"))
                    "tool_use" -> toolUses.add(block)
                    "server_tool_use" -> when (block.optString("name")) {
                        "web_search" -> {
                            val query = block.optJSONObject("input")?.optString("query").orEmpty()
                            onToolLog(context.getString(R.string.tool_log_web_search, query))
                        }
                        "web_fetch" -> {
                            val url = block.optJSONObject("input")?.optString("url").orEmpty()
                            onToolLog(context.getString(R.string.tool_log_web_fetch, url))
                        }
                    }
                    "compaction" -> onToolLog(context.getString(R.string.tool_log_compaction))
                    // web_search_tool_result 등 나머지 서버사이드 tool 결과 블록은 Anthropic이 이미
                    // 처리를 끝낸 상태로 오므로 우리가 따로 실행할 게 없다 — content 전체를 그대로
                    // 히스토리에 다시 담아 보내기만 하면 되고(아래 messages.put), 여기서는 무시한다.
                }
            }

            if (toolUses.isEmpty()) {
                return text.toString().ifBlank { context.getString(R.string.empty_reply) }
            }

            val toolResults = JSONArray()
            for (block in toolUses) {
                coroutineContext.ensureActive()
                val name = block.optString("name")
                val input = block.optJSONObject("input") ?: JSONObject()
                val toolUseId = block.optString("id")

                if (name in FILE_TOOL_NAMES) {
                    val resultText = FileSystemToolExecutor.execute(context, name, input, onConfirmRiskyAction)
                    onToolLog(context.getString(R.string.tool_log_generic, resultText))
                    toolResults.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", resultText),
                    )
                    continue
                }

                if (name in CONTACTS_TOOL_NAMES) {
                    val resultText = ContactsToolExecutor.execute(context, name, input, onRequestPermission)
                    onToolLog(context.getString(R.string.tool_log_generic, resultText))
                    toolResults.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", resultText),
                    )
                    continue
                }

                if (name in CALENDAR_TOOL_NAMES) {
                    val resultText = CalendarToolExecutor.execute(context, name, input, onRequestPermission, onConfirmRiskyAction)
                    onToolLog(context.getString(R.string.tool_log_generic, resultText))
                    toolResults.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", resultText),
                    )
                    continue
                }

                if (name in SMS_TOOL_NAMES) {
                    val resultText = SmsToolExecutor.execute(context, name, input, onRequestPermission, onConfirmRiskyAction)
                    onToolLog(context.getString(R.string.tool_log_generic, resultText))
                    toolResults.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", resultText),
                    )
                    continue
                }

                if (name in CLIPBOARD_TOOL_NAMES) {
                    val resultText = ClipboardToolExecutor.execute(context, name, input)
                    onToolLog(context.getString(R.string.tool_log_generic, resultText))
                    toolResults.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", resultText),
                    )
                    continue
                }

                if (name in INTENT_TOOL_NAMES) {
                    val resultText = IntentToolExecutor.execute(context, name, input)
                    onToolLog(context.getString(R.string.tool_log_generic, resultText))
                    toolResults.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", resultText),
                    )
                    continue
                }

                if (name in LOCATION_TOOL_NAMES) {
                    val resultText = LocationToolExecutor.execute(context, name, input, onRequestPermission)
                    onToolLog(context.getString(R.string.tool_log_generic, resultText))
                    toolResults.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", resultText),
                    )
                    continue
                }

                if (name in CALL_LOG_TOOL_NAMES) {
                    val resultText = CallLogToolExecutor.execute(context, name, input, onRequestPermission)
                    onToolLog(context.getString(R.string.tool_log_generic, resultText))
                    toolResults.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", resultText),
                    )
                    continue
                }

                if (name in DEVICE_CONTROL_TOOL_NAMES) {
                    val resultText = DeviceControlToolExecutor.execute(context, name, input)
                    onToolLog(context.getString(R.string.tool_log_generic, resultText))
                    toolResults.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", resultText),
                    )
                    continue
                }

                if (name == "todo_write") {
                    val items = input.optJSONArray("items") ?: JSONArray()
                    val todos = (0 until items.length()).map { i ->
                        val item = items.getJSONObject(i)
                        TodoItem(item.optString("content"), item.optString("status"))
                    }
                    onTodoUpdate(todos)
                    val resultText = context.getString(R.string.tool_result_todo_write, todos.size)
                    onToolLog(context.getString(R.string.tool_log_generic, resultText))
                    toolResults.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", resultText),
                    )
                    continue
                }

                if (name == "confirm_risky_action") {
                    val action = input.optString("action")
                    val reason = input.optString("reason")
                    val message = if (reason.isNotBlank()) "$action\n\n$reason" else action
                    onToolLog(context.getString(R.string.tool_log_confirming_risky_action, action))
                    val allowed = onConfirmRiskyAction(message)
                    val resultText = context.getString(
                        if (allowed) R.string.tool_result_risky_action_allowed else R.string.tool_result_risky_action_denied,
                    )
                    onToolLog(context.getString(R.string.tool_log_generic, resultText))
                    toolResults.put(
                        JSONObject().put("type", "tool_result").put("tool_use_id", toolUseId).put("content", resultText),
                    )
                    continue
                }

                val service = AssistantAccessibilityService.instance
                if (service == null) {
                    onToolLog(context.getString(R.string.tool_log_accessibility_off))
                    return context.getString(R.string.reply_accessibility_off)
                }

                // tap/swipe/type_text/key_event/wait — 실행 직후 최신 화면 스크린샷을 같은
                // tool_result에 바로 담아 돌려준다. 예전엔 이 결과가 텍스트뿐이라 모델이 화면을
                // 보려면 view_screen을 한 번 더 불러야 했는데(턴 수가 실질적으로 2배), 이제는
                // 액션 하나당 한 턴으로 끝난다.
                var isToolInputError = false
                val r = try {
                    ScreenActionExecutor.execute(context, service, name, input, scale)
                } catch (e: JSONException) {
                    isToolInputError = true
                    context.getString(R.string.tool_result_invalid_input, e.message)
                }
                onToolLog(context.getString(R.string.tool_log_generic, r))
                delay(800)

                val (png, newScale) = ScreenActionExecutor.captureAndResize(service)
                scale = newScale
                val bitmap = decodeAndNotifyScreenshot(png, onScreenshot)
                var resultText = r
                if (bitmap != null) {
                    val fingerprint = screenFingerprint(bitmap)
                    val previous = lastScreenFingerprint
                    stuckScreenCount = if (previous != null && screensLookTheSame(previous, fingerprint)) {
                        stuckScreenCount + 1
                    } else {
                        0
                    }
                    lastScreenFingerprint = fingerprint
                    if (stuckScreenCount == STUCK_SCREEN_NUDGE_THRESHOLD) {
                        // 완전히 멈추기 전에 한 번, 같은 동작을 그만 반복하고 다른 방법을 시도하라고
                        // 귀띔해준다 — 이걸로 스스로 회복하면 아래 하드 스톱까지 안 가도 된다.
                        onToolLog(context.getString(R.string.tool_log_stuck_nudge))
                        resultText += "\n\n" + context.getString(R.string.tool_result_stuck_nudge)
                    }
                    if (stuckScreenCount >= STUCK_SCREEN_REPEAT_LIMIT) {
                        // 여기서 바로 끝내지 않고, 이번 스텝의 tool_result(스크린샷 포함)까지 정상
                        // 마무리한 뒤 루프 밖에서 모델에게 이유를 설명하게 한다.
                        stuckDetected = true
                    }
                }
                stripOldScreenshotImages(messages)
                val toolResult = JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", toolUseId)
                    .put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "text").put("text", resultText))
                            .put(claudeImageBlock(png, "image/png")),
                    )
                if (isToolInputError) toolResult.put("is_error", true)
                toolResults.put(toolResult)
            }

            messages.put(JSONObject().put("role", "user").put("content", toolResults))

            if (stuckDetected) {
                return explainStuckAndFinish(context, model, apiKey, systemPrompt, messages, onToolLog)
            }
        }

        return context.getString(R.string.reply_too_many_steps)
    }

    private fun claudeImageBlock(bytes: ByteArray, mediaType: String): JSONObject {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return JSONObject()
            .put("type", "image")
            .put(
                "source",
                JSONObject()
                    .put("type", "base64")
                    .put("media_type", mediaType)
                    .put("data", b64),
            )
    }

    /** 사용자 첨부(이미지/PDF/텍스트 계열)가 있으면 텍스트+이미지+문서 블록 배열로, 없으면 기존처럼 순수 문자열로 content를 만든다. */
    private fun claudeMessageContent(context: Context, msg: ChatMessage): Any {
        val hasAttachment = msg.imageUri != null || msg.fileUri != null || msg.fileExtractedText != null
        if (!hasAttachment) return msg.content

        val blocks = JSONArray()
        val text = textWithExtractedFile(msg)
        if (text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", text))
        msg.imageUri?.let { uri -> readAttachedFileBytes(context, uri)?.let { blocks.put(claudeImageBlock(it, "image/jpeg")) } }
        if (msg.fileMimeType == "application/pdf") {
            msg.fileUri?.let { uri ->
                readAttachedFileBytes(context, uri)?.let { bytes ->
                    blocks.put(
                        JSONObject()
                            .put("type", "document")
                            .put(
                                "source",
                                JSONObject()
                                    .put("type", "base64")
                                    .put("media_type", "application/pdf")
                                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                            ),
                    )
                }
            }
        }
        return blocks
    }

    private fun claudeToolsSchema(): JSONArray {
        fun obj(build: JSONObject.() -> Unit) = JSONObject().apply(build)
        val tools = JSONArray()
            .put(
                obj {
                    put("name", "tap")
                    put("description", "화면의 (x, y)를 탭한다. 좌표는 최근에 받은 화면 스크린샷 기준.")
                    put("strict", true)
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("x", obj { put("type", "integer") }); put("y", obj { put("type", "integer") }) })
                            put("required", JSONArray(listOf("x", "y")))
                            put("additionalProperties", false)
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "swipe")
                    put("description", "(x1,y1)에서 (x2,y2)로 스와이프한다. 좌표는 최근에 받은 화면 스크린샷 기준. duration_ms를 잘 모르겠으면 300을 쓴다.")
                    put("strict", true)
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("x1", obj { put("type", "integer") })
                                    put("y1", obj { put("type", "integer") })
                                    put("x2", obj { put("type", "integer") })
                                    put("y2", obj { put("type", "integer") })
                                    put("duration_ms", obj { put("type", "integer") })
                                },
                            )
                            put("required", JSONArray(listOf("x1", "y1", "x2", "y2", "duration_ms")))
                            put("additionalProperties", false)
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "type_text")
                    put("description", "현재 포커스된 입력창에 텍스트를 입력한다.")
                    put("strict", true)
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("text", obj { put("type", "string") }) })
                            put("required", JSONArray(listOf("text")))
                            put("additionalProperties", false)
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "key_event")
                    put("description", "시스템 키를 누른다.")
                    put("strict", true)
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put(
                                        "keycode",
                                        obj {
                                            put("type", "string")
                                            put("enum", JSONArray(listOf("BACK", "HOME", "ENTER", "APP_SWITCH", "DEL")))
                                        },
                                    )
                                },
                            )
                            put("required", JSONArray(listOf("keycode")))
                            put("additionalProperties", false)
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "wait")
                    put("description", "화면 전환 등을 기다리기 위해 지정한 초만큼 대기한다.")
                    put("strict", true)
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("seconds", obj { put("type", "number") }) })
                            put("required", JSONArray(listOf("seconds")))
                            put("additionalProperties", false)
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "list_files")
                    put("description", "휴대폰 로컬 저장소의 폴더 안 파일/폴더 목록을 본다. path를 비우면 저장소 최상위(내부 저장소 루트)를 본다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("path", obj { put("type", "string"); put("description", "예: \"Download\", \"Documents/project\". 비우면 저장소 최상위.") }) })
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "search_files")
                    put("description", "휴대폰 로컬 저장소에서 path 아래 하위 폴더까지 전부 재귀적으로 훑어 query와 일치하는 파일을 찾는다. list_files는 지정한 폴더 하나만 보여주지만, 이 도구는 파일이 어느 폴더에 있는지 모를 때 통째로 찾아준다. 기본은 파일명 검색이고, search_content를 true로 주면 일반 텍스트 파일(txt/md/csv/json/log)의 내용도 함께 검색한다(docx/xlsx 등은 내용 검색 미지원). 결과가 많으면 상위 일부만 보여준다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("path", obj { put("type", "string"); put("description", "검색을 시작할 폴더. 예: \"Download\". 비우면 저장소 최상위 전체를 검색.") })
                                    put("query", obj { put("type", "string"); put("description", "찾을 파일명(부분 일치) 또는 내용 문자열") })
                                    put("search_content", obj { put("type", "boolean"); put("description", "true면 파일명뿐 아니라 일반 텍스트 파일의 내용도 검색(기본 false)") })
                                },
                            )
                            put("required", JSONArray(listOf("query")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "read_file")
                    put("description", "휴대폰 로컬 저장소의 텍스트 계열 파일(txt/md/csv/json/docx/xlsx) 내용을 읽는다. PDF와 이미지는 지원하지 않는다 — 채팅 첨부를 이용하라고 안내한다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("path", obj { put("type", "string"); put("description", "list_files로 확인한 파일의 전체 또는 상대 경로") }) })
                            put("required", JSONArray(listOf("path")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "write_file")
                    put("description", "휴대폰 로컬 저장소에 파일을 새로 만들거나 덮어쓴다. txt/md/csv/json/log는 content를 그대로 저장한다. docx는 content의 줄바꿈마다 새 문단으로, xlsx는 줄바꿈마다 새 행 · 탭(\\t)마다 새 셀로 저장한다(서식 없이 텍스트/값만 담김 — read_file이 xlsx를 탭으로 이어붙인 텍스트로 돌려주는 것과 같은 규칙). 실행 전 사용자에게 확인을 구하고, 거부하면 아무 것도 바뀌지 않는다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("path", obj { put("type", "string"); put("description", "만들거나 덮어쓸 파일의 전체 또는 상대 경로") })
                                    put("content", obj { put("type", "string"); put("description", "파일에 쓸 전체 내용") })
                                },
                            )
                            put("required", JSONArray(listOf("path", "content")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "find_replace_file")
                    put("description", "휴대폰 로컬 저장소의 기존 파일에서 특정 문자열(find)을 찾아 다른 문자열(replace)로 바꾼다. write_file과 달리 파일을 통째로 새로 만들지 않아서, docx/xlsx의 기존 서식(글꼴/색/표/이미지 등)을 그대로 유지한 채 텍스트 일부만 고칠 수 있다. 단, docx/xlsx에서 find 문자열이 서로 다른 서식 조각(run)에 걸쳐 나뉘어 있으면 못 찾을 수 있다 — 이땐 write_file로 통째로 다시 쓰는 걸 고려하라. txt/md/csv/json/log에도 쓸 수 있다. 일치하는 곳이 여러 곳이면 전부 바꾼다. 실행 전 사용자에게 확인을 구하고, 거부하면 아무 것도 바뀌지 않는다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("path", obj { put("type", "string"); put("description", "수정할 파일의 전체 또는 상대 경로") })
                                    put("find", obj { put("type", "string"); put("description", "찾을 문자열 (정확히 일치해야 함)") })
                                    put("replace", obj { put("type", "string"); put("description", "바꿔 넣을 문자열") })
                                },
                            )
                            put("required", JSONArray(listOf("path", "find", "replace")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "delete_file")
                    put("description", "휴대폰 로컬 저장소의 파일 하나를 삭제한다. 폴더는 삭제할 수 없다. 실행 전 사용자에게 확인을 구하고, 거부하면 삭제되지 않는다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("path", obj { put("type", "string"); put("description", "삭제할 파일의 전체 또는 상대 경로") }) })
                            put("required", JSONArray(listOf("path")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "zip_files")
                    put("description", "여러 파일/폴더를 zip으로 압축한다. 실행 전 사용자에게 확인을 구하고, 거부하면 압축되지 않는다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put(
                                        "paths",
                                        obj {
                                            put("type", "array")
                                            put("items", obj { put("type", "string") })
                                            put("description", "압축할 파일/폴더 경로 목록")
                                        },
                                    )
                                    put("output_path", obj { put("type", "string"); put("description", "결과 zip 파일 경로(.zip 확장자는 자동으로 붙음)") })
                                },
                            )
                            put("required", JSONArray(listOf("paths", "output_path")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "unzip_file")
                    put("description", "zip 파일의 압축을 푼다. 실행 전 사용자에게 확인을 구하고, 거부하면 압축이 풀리지 않는다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("path", obj { put("type", "string"); put("description", "압축을 풀 zip 파일 경로") })
                                    put("output_dir", obj { put("type", "string"); put("description", "압축을 풀 폴더(선택, 비우면 zip 파일명과 같은 폴더 새로 생성)") })
                                },
                            )
                            put("required", JSONArray(listOf("path")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "search_contacts")
                    put("description", "이름으로 연락처를 검색해 전화번호를 가져온다. READ_CONTACTS 권한이 아직 없으면 이 도구를 처음 호출하는 순간 사용자에게 권한을 요청하고, 거부하면 실행되지 않는다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("query", obj { put("type", "string"); put("description", "검색할 이름(부분 일치)") }) })
                            put("required", JSONArray(listOf("query")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "save_contact")
                    put("description", "새 연락처를 저장한다. WRITE_CONTACTS 권한이 아직 없으면 이 도구를 처음 호출하는 순간 사용자에게 권한을 요청한다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("name", obj { put("type", "string"); put("description", "저장할 이름") })
                                    put("phone_number", obj { put("type", "string"); put("description", "저장할 전화번호") })
                                },
                            )
                            put("required", JSONArray(listOf("name", "phone_number")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "list_calendar_events")
                    put("description", "지정한 기간의 캘린더 일정을 조회한다. 결과의 각 줄 앞 [ID:숫자]가 그 일정의 event_id로, update_calendar_event/delete_calendar_event에 그대로 쓴다. READ_CALENDAR 권한이 아직 없으면 이 도구를 처음 호출하는 순간 사용자에게 권한을 요청한다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("from", obj { put("type", "string"); put("description", "조회 시작 시각, \"yyyy-MM-dd HH:mm\" 형식(예: \"2026-09-05 00:00\"). 비우면 지금부터.") })
                                    put("to", obj { put("type", "string"); put("description", "조회 종료 시각, 같은 형식. 비우면 from으로부터 7일 뒤까지.") })
                                },
                            )
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "add_calendar_event")
                    put("description", "캘린더에 새 일정을 추가한다. READ_CALENDAR/WRITE_CALENDAR 권한이 아직 없으면 이 도구를 처음 호출하는 순간 사용자에게 권한을 요청한다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("title", obj { put("type", "string"); put("description", "일정 제목") })
                                    put("start", obj { put("type", "string"); put("description", "시작 시각, \"yyyy-MM-dd HH:mm\" 형식(예: \"2026-09-05 15:00\")") })
                                    put("end", obj { put("type", "string"); put("description", "종료 시각, 같은 형식") })
                                    put("description", obj { put("type", "string"); put("description", "일정 설명(선택)") })
                                },
                            )
                            put("required", JSONArray(listOf("title", "start", "end")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "update_calendar_event")
                    put("description", "기존 일정을 수정한다(전달한 필드만 바뀜). event_id는 list_calendar_events 결과의 [ID:숫자]에서 가져온다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("event_id", obj { put("type", "integer"); put("description", "수정할 일정의 ID") })
                                    put("title", obj { put("type", "string"); put("description", "새 제목(선택)") })
                                    put("start", obj { put("type", "string"); put("description", "새 시작 시각, \"yyyy-MM-dd HH:mm\" 형식(선택)") })
                                    put("end", obj { put("type", "string"); put("description", "새 종료 시각, 같은 형식(선택)") })
                                    put("description", obj { put("type", "string"); put("description", "새 설명(선택)") })
                                },
                            )
                            put("required", JSONArray(listOf("event_id")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "delete_calendar_event")
                    put("description", "일정을 삭제한다. event_id는 list_calendar_events 결과의 [ID:숫자]에서 가져온다. 실행 전 사용자에게 확인을 구하고, 거부하면 삭제되지 않는다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("event_id", obj { put("type", "integer"); put("description", "삭제할 일정의 ID") }) })
                            put("required", JSONArray(listOf("event_id")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "search_sms")
                    put("description", "받은 문자함에서 문자를 검색한다(최신순). READ_SMS 권한이 아직 없으면 이 도구를 처음 호출하는 순간 사용자에게 권한을 요청한다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("query", obj { put("type", "string"); put("description", "검색할 문자 내용(부분 일치). 비우면 최근 문자를 최신순으로 반환.") }) })
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "send_sms")
                    put("description", "문자를 발송한다. SEND_SMS 권한이 아직 없으면 이 도구를 처음 호출하는 순간 사용자에게 권한을 요청하고, 권한이 있어도 실제 발송 전 항상 사용자 확인을 한 번 더 구한다 — 확인 없이 confirm_risky_action을 별도로 호출할 필요는 없다(이 도구 자체에 확인 절차가 포함돼 있음).")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("phone_number", obj { put("type", "string"); put("description", "받는 사람 전화번호. 이름만 알면 search_contacts로 먼저 번호를 찾는다.") })
                                    put("message", obj { put("type", "string"); put("description", "보낼 메시지 내용") })
                                },
                            )
                            put("required", JSONArray(listOf("phone_number", "message")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "read_clipboard")
                    put("description", "클립보드에 현재 복사되어 있는 텍스트를 가져온다. 권한이 필요 없다.")
                    put("input_schema", obj { put("type", "object"); put("properties", obj {}) })
                },
            )
            .put(
                obj {
                    put("name", "write_clipboard")
                    put("description", "텍스트를 클립보드에 복사한다. 권한이 필요 없다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("text", obj { put("type", "string"); put("description", "클립보드에 넣을 텍스트") }) })
                            put("required", JSONArray(listOf("text")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "set_alarm")
                    put("description", "시계 앱에 알람을 설정한다. 별도 확인 팝업 없이 즉시 설정된다(알람은 쉽게 끄거나 바꿀 수 있어 위험도가 낮음). 권한이 필요 없다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("hour", obj { put("type", "integer"); put("description", "시(0~23, 24시간제)") })
                                    put("minute", obj { put("type", "integer"); put("description", "분(0~59)") })
                                    put("label", obj { put("type", "string"); put("description", "알람 라벨(선택)") })
                                },
                            )
                            put("required", JSONArray(listOf("hour", "minute")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "set_timer")
                    put("description", "시계 앱에 타이머를 설정한다. 별도 확인 팝업 없이 즉시 설정된다. 권한이 필요 없다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("seconds", obj { put("type", "integer"); put("description", "타이머 길이(초)") })
                                    put("label", obj { put("type", "string"); put("description", "타이머 라벨(선택)") })
                                },
                            )
                            put("required", JSONArray(listOf("seconds")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "open_maps")
                    put("description", "지도 앱에서 장소를 검색하거나 길찾기를 연다. 권한이 필요 없다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("query", obj { put("type", "string"); put("description", "검색할 장소/주소") }) })
                            put("required", JSONArray(listOf("query")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "compose_email")
                    put("description", "메일 앱에 받는사람/제목/내용을 채운 작성 화면을 연다. 실제 전송은 사용자가 메일 앱에서 직접 눌러야 하므로 권한도, 확인 절차도 필요 없다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("to", obj { put("type", "string"); put("description", "받는 사람 이메일 주소(선택)") })
                                    put("subject", obj { put("type", "string"); put("description", "제목(선택)") })
                                    put("body", obj { put("type", "string"); put("description", "본문(선택)") })
                                },
                            )
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "open_dialer")
                    put("description", "전화 앱을 번호까지 채워서 연다. 실제 통화 버튼은 사용자가 직접 눌러야 하므로 권한이 필요 없다. 이름만 알면 search_contacts로 먼저 번호를 찾는다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("phone_number", obj { put("type", "string"); put("description", "걸 전화번호") }) })
                            put("required", JSONArray(listOf("phone_number")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "get_current_location")
                    put("description", "현재 위치(주소 및 좌표)를 가져온다. ACCESS_FINE_LOCATION 권한이 아직 없으면 이 도구를 처음 호출하는 순간 사용자에게 권한을 요청한다.")
                    put("input_schema", obj { put("type", "object"); put("properties", obj {}) })
                },
            )
            .put(
                obj {
                    put("name", "search_call_log")
                    put("description", "통화 기록을 검색한다(최신순). READ_CALL_LOG 권한이 아직 없으면 이 도구를 처음 호출하는 순간 사용자에게 권한을 요청한다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("query", obj { put("type", "string"); put("description", "이름 또는 번호(부분 일치). 비우면 최근 통화 전체.") }) })
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "set_flashlight")
                    put("description", "손전등을 켜거나 끈다. 권한이 필요 없다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put("properties", obj { put("on", obj { put("type", "boolean"); put("description", "true면 켜기, false면 끄기") }) })
                            put("required", JSONArray(listOf("on")))
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "set_volume")
                    put("description", "미디어/벨소리/알람 볼륨을 특정 퍼센트로 맞춘다. 권한이 필요 없다.")
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("stream", obj { put("type", "string"); put("enum", JSONArray(listOf("media", "ring", "alarm"))); put("description", "조절할 볼륨 종류") })
                                    put("level_percent", obj { put("type", "integer"); put("description", "0~100 사이 목표 볼륨(%)") })
                                },
                            )
                            put("required", JSONArray(listOf("stream", "level_percent")))
                        },
                    )
                },
            )
            .put(
                obj {
                    // Anthropic 서버가 검색까지 대신 수행해서 결과를 같은 응답에 담아주는 서버사이드 tool.
                    // 우리 쪽에서 따로 실행할 게 없다 — max_uses로 한 대화당 남용을 제한한다.
                    put("type", "web_search_20260209")
                    put("name", "web_search")
                    put("max_uses", 5)
                },
            )
            .put(
                obj {
                    // web_search와 마찬가지로 서버사이드 tool — 우리 쪽에서 실행할 게 없다. 대화(사용자
                    // 메시지 또는 이전 web_search 결과)에 이미 등장한 URL만 가져올 수 있다는 제약이 있다
                    // (임의 URL을 즉석에서 조회할 순 없음) — 사용자가 링크를 붙여넣고 요약을 요청하는
                    // 시나리오엔 그대로 맞는다.
                    put("type", "web_fetch_20260209")
                    put("name", "web_fetch")
                    put("max_uses", 5)
                },
            )
            .put(
                obj {
                    put("name", "todo_write")
                    put(
                        "description",
                        "화면 조작이나 조사가 여러 단계로 이어지는 복잡한 작업의 할 일 목록을 관리한다. " +
                            "호출할 때마다 목록 전체를 새로 제출한다(부분 수정이 아님). 짧고 단순한 작업에는 쓰지 않는다.",
                    )
                    put("strict", true)
                    put(
                        "input_schema",
                        obj {
                            put(
                                "properties",
                                obj {
                                    put(
                                        "items",
                                        obj {
                                            put("type", "array")
                                            put(
                                                "items",
                                                obj {
                                                    put("type", "object")
                                                    put(
                                                        "properties",
                                                        obj {
                                                            put("content", obj { put("type", "string") })
                                                            put(
                                                                "status",
                                                                obj {
                                                                    put("type", "string")
                                                                    put("enum", JSONArray(listOf("pending", "in_progress", "completed")))
                                                                },
                                                            )
                                                        },
                                                    )
                                                    put("required", JSONArray(listOf("content", "status")))
                                                    put("additionalProperties", false)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                            put("type", "object")
                            put("required", JSONArray(listOf("items")))
                            put("additionalProperties", false)
                        },
                    )
                },
            )
            .put(
                obj {
                    put("name", "confirm_risky_action")
                    put(
                        "description",
                        "결제, 구매, 주문/예약 확정, 발송, 삭제처럼 되돌리기 어렵고 실제 돈이나 중요한 결과가 걸린 " +
                            "화면 동작을 실행하기 직전에 반드시 호출해서 사용자 확인을 받는다. 사용자가 거부하면 그 " +
                            "동작을 실행하지 말고 다른 방법을 찾거나 작업을 중단한다.",
                    )
                    put("strict", true)
                    put(
                        "input_schema",
                        obj {
                            put("type", "object")
                            put(
                                "properties",
                                obj {
                                    put("action", obj { put("type", "string"); put("description", "지금 실행하려는 동작 (예: \"영화 티켓 결제하기 버튼 탭\")") })
                                    put("reason", obj { put("type", "string"); put("description", "왜 확인이 필요한지 (예: \"실제 결제가 발생함\")") })
                                },
                            )
                            put("required", JSONArray(listOf("action", "reason")))
                            put("additionalProperties", false)
                        },
                    )
                },
            )
        // 마지막 tool에 캐시 breakpoint를 달아 tools 스키마 전체를 캐싱한다 — 이 목록은 스텝마다
        // 안 바뀌므로, 첫 스텝에서만 원가로 캐시에 쓰고 이후 스텝은 훨씬 싼 캐시 읽기로 처리된다.
        tools.getJSONObject(tools.length() - 1).put("cache_control", JSONObject().put("type", "ephemeral"))
        return tools
    }
}
