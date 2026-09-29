# 김비서 (SecretaryKim)

Claude API로 동작하는 **안드로이드 개인 비서 앱**입니다. Claude Desktop의 모바일 버전을 목표로, 채팅뿐 아니라 LLM이 폰의 화면·파일·연락처·캘린더·문자 등을 직접 다룰 수 있게(tool use) 만들었습니다.

- 다운로드 사이트: https://secretarykim.net/
- 플레이스토어/앱스토어에 올릴 수 없는 성격의 앱(접근성 서비스로 화면 조작, SMS 전송 등)이라 **APK 직접 설치(사이드로드)** 방식으로 배포합니다.
- 사용자가 자기 Anthropic API 키를 앱에 입력해 쓰는 **BYOK** 구조입니다. 키는 기기 안에서 `EncryptedSharedPreferences`(AES-256)로만 저장되고, 서버를 거치지 않고 앱이 `api.anthropic.com`을 직접 호출합니다.

## 구성

| 폴더 | 내용 |
|---|---|
| [android/](android/) | 안드로이드 앱 (Kotlin, Jetpack Compose, minSdk 30 / targetSdk 36) |
| [backend/](backend/) | 소개·다운로드·리뷰·Q&A 웹사이트 (Next.js, Vercel 배포, Upstash Redis). LLM 호출은 하지 않음 |
| [adb_test/](adb_test/) | 앱 개발 전 ADB로 폰을 조작하는 에이전트 루프 실험 (Claude / Gemini / GPT 비교) |
| [시스템프롬프트.md](시스템프롬프트.md) | 현재 시스템 프롬프트 전문 (한/영) |
| [실험결과.md](실험결과.md), [실험결과v2.md](실험결과v2.md), [입출력관찰v3.md](입출력관찰v3.md) 등 | 화면 조작 방식(스크린샷 vs 접근성 노드 트리 등)에 대한 실험 기록 |

## LLM 관련 구현 위치

LLM 호출 로직은 전부 한 파일에 모여 있습니다: [ChatRepository.kt](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt)

### 시스템 프롬프트

| 무엇 | 위치 |
|---|---|
| 한국어 프롬프트 `SCREEN_AGENT_SYSTEM_PROMPT_KO` | [ChatRepository.kt:78](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt#L78) |
| 영어 프롬프트 `SCREEN_AGENT_SYSTEM_PROMPT_EN` | [ChatRepository.kt:97](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt#L97) |
| 언어 선택 + 사용자 지정 지침 합치기 `buildSystemPrompt()` | [ChatRepository.kt:118](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt#L118) |
| 사용자 지정 지침("고정 프롬프트") 저장 | [SecureKeyStore.kt](android/app/src/main/java/com/ybs/assistant/data/SecureKeyStore.kt) (`custom_system_prompt`), 편집 UI는 [SettingsScreen.kt](android/app/src/main/java/com/ybs/assistant/ui/SettingsScreen.kt) |
| 전문 열람용 문서 | [시스템프롬프트.md](시스템프롬프트.md) |

기기 언어가 한국어면 KO, 아니면 EN을 쓰고, 설정에 사용자 지침이 있으면 프롬프트 뒤에 덧붙입니다.

### 요청 구성과 에이전트 루프

| 무엇 | 위치 |
|---|---|
| 도구 스키마 목록 `claudeToolsSchema()` (모든 tool의 이름·설명·파라미터 JSON Schema) | [ChatRepository.kt:717](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt#L717) |
| API 요청 바디/헤더 `buildClaudeRequest()` (system, tools, 서버 측 compaction 베타 포함) | [ChatRepository.kt:319](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt#L319) |
| tool use 루프 `converseClaude()` (응답의 `tool_use`를 실행 → `tool_result`로 돌려보내며 반복, 최대 `MAX_TOOL_STEPS`회) | [ChatRepository.kt:400](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt#L400) |
| 도구 이름 → 실행기 분기 (`*_TOOL_NAMES` 집합) | 집합 정의 [ChatRepository.kt:48-57](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt#L48-L57), 분기 [ChatRepository.kt:494-618](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt#L494-L618) |
| 기본 모델 | [ModelCatalog.kt](android/app/src/main/java/com/ybs/assistant/data/ModelCatalog.kt) |

### LLM이 호출할 수 있는 도구

스키마(LLM에게 보이는 정의)는 모두 `claudeToolsSchema()`에 있고, **실제 실행 코드**는 아래 `agent/` 폴더의 실행기에 있습니다.

| 분류 | 도구 | 실행 코드 |
|---|---|---|
| 화면 조작 | `tap`, `swipe`, `type_text`, `key_event`, `wait` | [ScreenActionExecutor.kt](android/app/src/main/java/com/ybs/assistant/agent/ScreenActionExecutor.kt) → 접근성 서비스 [AssistantAccessibilityService.kt](android/app/src/main/java/com/ybs/assistant/accessibility/AssistantAccessibilityService.kt) (`takeScreenshot`, `dispatchGesture`, `performGlobalAction`) |
| 파일 | `list_files`, `search_files`, `read_file`, `write_file`, `find_replace_file`, `delete_file`, `zip_files`, `unzip_file` | [FileSystemToolExecutor.kt](android/app/src/main/java/com/ybs/assistant/agent/FileSystemToolExecutor.kt) (docx/xlsx 생성·편집은 [DocumentBuilder.kt](android/app/src/main/java/com/ybs/assistant/data/DocumentBuilder.kt), [DocumentEditor.kt](android/app/src/main/java/com/ybs/assistant/data/DocumentEditor.kt)) |
| 연락처 | `search_contacts`, `save_contact` | [ContactsToolExecutor.kt](android/app/src/main/java/com/ybs/assistant/agent/ContactsToolExecutor.kt) |
| 캘린더 | `list_calendar_events`, `add_calendar_event`, `update_calendar_event`, `delete_calendar_event` | [CalendarToolExecutor.kt](android/app/src/main/java/com/ybs/assistant/agent/CalendarToolExecutor.kt) |
| 문자 | `search_sms`, `send_sms` | [SmsToolExecutor.kt](android/app/src/main/java/com/ybs/assistant/agent/SmsToolExecutor.kt) |
| 통화 기록 | `search_call_log` | [CallLogToolExecutor.kt](android/app/src/main/java/com/ybs/assistant/agent/CallLogToolExecutor.kt) |
| 위치 | `get_current_location` | [LocationToolExecutor.kt](android/app/src/main/java/com/ybs/assistant/agent/LocationToolExecutor.kt) |
| 클립보드 | `read_clipboard`, `write_clipboard` | [ClipboardToolExecutor.kt](android/app/src/main/java/com/ybs/assistant/agent/ClipboardToolExecutor.kt) |
| 시스템 연동 (인텐트) | `set_alarm`, `set_timer`, `open_maps`, `compose_email`, `open_dialer` | [IntentToolExecutor.kt](android/app/src/main/java/com/ybs/assistant/agent/IntentToolExecutor.kt) |
| 기기 제어 | `set_flashlight`, `set_volume` | [DeviceControlToolExecutor.kt](android/app/src/main/java/com/ybs/assistant/agent/DeviceControlToolExecutor.kt) |
| 작업 관리 | `todo_write` (할 일 목록을 UI에 표시) | 앱에서 직접 처리: [ChatRepository.kt:575](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt#L575), 데이터 [TodoItem.kt](android/app/src/main/java/com/ybs/assistant/data/TodoItem.kt) |
| 위험 작업 확인 | `confirm_risky_action` (결제·발송·삭제 전에 사용자 승인) | 분기 [ChatRepository.kt:590](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt#L590), 스키마 [ChatRepository.kt:1352](android/app/src/main/java/com/ybs/assistant/network/ChatRepository.kt#L1352) → 확인 상태 [ChatViewModel.kt](android/app/src/main/java/com/ybs/assistant/ui/ChatViewModel.kt) (`pendingRiskyActionConfirmation`), 다이얼로그 [ChatScreen.kt](android/app/src/main/java/com/ybs/assistant/ui/ChatScreen.kt) |
| 웹 검색 / 페이지 조회 | `web_search`, `web_fetch` | 실행 코드 없음 — Anthropic 서버 측 도구(`web_search_20260209`, `web_fetch_20260209`)라 스키마만 등록하고 서버가 실행 (`max_uses` 5) |

### 안전장치

- 파일 쓰기/삭제/압축, 캘린더 수정/삭제 등은 실행기가 `confirm` 콜백으로 **사용자 확인**을 받은 뒤에만 실제로 수행합니다.
- 연락처·문자·캘린더·위치·통화기록 도구는 필요한 안드로이드 런타임 권한이 없으면 실행 전에 권한을 요청합니다.
- 시스템 프롬프트가 결제·주문·발송·삭제 같은 되돌리기 어려운 화면 동작 전에 `confirm_risky_action`을 호출하도록 지시합니다.
- `send_sms`는 권한이 있어도 발송 직전에 도구 자체가 사용자 확인을 한 번 더 받습니다.
- 안드로이드 시스템 권한 요청 팝업은 접근성 서비스의 자동 터치가 차단되므로, 프롬프트에서 LLM이 누르려 시도하지 말고 사용자에게 직접 허용을 안내하도록 지시합니다.

## 빌드

### 안드로이드 앱

```bash
cd android
./gradlew assembleDebug        # Java 21 필요
```

첫 실행 시 앱에서 Anthropic API 키를 입력하고, 안내에 따라 접근성 서비스 등 권한을 켭니다.

### 웹사이트

```bash
cd backend
cp .env.example .env.local     # Upstash Redis 값 등 채우기
npm install
npm run dev
```

### adb_test 실험 스크립트

API 키가 저장된 파일 경로를 환경변수로 지정합니다.

```bash
export ANTHROPIC_API_KEY_FILE=/path/to/claude_api_key.txt
export ADB_PATH=/path/to/adb      # 선택 (기본값: PATH의 adb)
python adb_test/agent_loop.py
```

## 참고

- 접근성 서비스로 화면을 읽고 조작하는 앱이라 스토어 정책상 배포가 불가능하며, 개인 사용/포트폴리오 목적입니다.
- API 키, 서명 키, `.env*` 파일은 저장소에 포함하지 않았습니다.
