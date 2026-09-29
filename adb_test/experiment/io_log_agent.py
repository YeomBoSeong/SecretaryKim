# -*- coding: utf-8 -*-
"""
입력 토큰 절감 분석용: 특정 작업을 실제 폰에서 실행시키면서
매 스텝마다 Claude API에 실제로 보내는 요청(입력)과 받는 응답(출력)을
사람이 읽을 수 있는 로그로 남긴다. (screenshot_agent_v2.py 로직 재사용)

이미지 base64 원본 데이터는 로그에 그대로 남기면 너무 커서 읽기 힘들고
분석에도 큰 의미가 없으므로, 바이트 수 + 예상 이미지 토큰 수로 요약해서 기록한다.
텍스트(시스템 지시문, tool 스키마, tool_result 텍스트 등)는 전부 그대로 남긴다.
"""

import base64
import io
import json
import sys
import time
from pathlib import Path

from anthropic import Anthropic
from PIL import Image

sys.stdout.reconfigure(encoding="utf-8")
sys.path.insert(0, str(Path(__file__).parent))
from common import (
    ADB_PATH,
    API_KEY_PATH,
    MODEL,
    KIMBISISEO_PACKAGE,
    UsageTracker,
    adb,
    get_screen_size,
    restore_original_ime,
    type_text_via_adb_keyboard,
)

MAX_STEPS = 20
RESIZE_MAX_DIM = 1024

YOUTUBE_PACKAGE = "com.google.android.youtube"

TASK_DESCRIPTION = (
    "사용자가 김비서 채팅 화면에서 방금 '유튜브에서 아이유 좋은날 검색해줘'라고 "
    "요청했습니다. 유튜브 앱은 아직 실행되어 있지 않을 수 있습니다 — 필요하면 먼저 "
    "화면을 조작해서 유튜브 앱을 직접 찾아 실행한 뒤, 검색창에 '아이유 좋은날'을 "
    "입력해서 검색하세요. 검색 결과 화면이 뜨면 작업을 끝내세요 (영상을 재생할 필요는 없습니다)."
)

NAV_TIP = (
    "참고: 앱을 직접 찾아야 한다면, 홈 화면에서 위로 스와이프하면 전체 앱 목록(앱 서랍)이 열리고, "
    "그 안의 검색창에 앱 이름을 입력하면 빠르게 찾을 수 있습니다."
)

LOG_PATH = Path(__file__).parent / "io_log_youtube.txt"
RESULT_PATH = Path(__file__).parent / "result_io_log_youtube.json"

_log_lines = []


def log(line=""):
    print(line)
    _log_lines.append(line)


def force_stop_and_start_from_kimbisiseo_youtube():
    adb("shell", "am", "force-stop", YOUTUBE_PACKAGE)
    adb("shell", "am", "force-stop", KIMBISISEO_PACKAGE)
    time.sleep(1.0)
    adb("shell", "input", "keyevent", "HOME")
    time.sleep(0.5)
    adb("shell", "monkey", "-p", KIMBISISEO_PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(2.5)


def take_screenshot():
    import subprocess

    result = subprocess.run([ADB_PATH, "exec-out", "screencap", "-p"], capture_output=True, timeout=15)
    return result.stdout


def resize_for_model(png_bytes):
    img = Image.open(io.BytesIO(png_bytes)).convert("RGB")
    w, h = img.size
    scale = RESIZE_MAX_DIM / max(w, h)
    if scale < 1:
        img = img.resize((int(w * scale), int(h * scale)))
    else:
        scale = 1.0
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    data = buf.getvalue()
    return data, scale, img.size


def image_block(png_bytes):
    b64 = base64.b64encode(png_bytes).decode("ascii")
    return {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": b64}}, len(b64), len(png_bytes)


def est_image_tokens(w, h):
    # Anthropic 공식 근사식: tokens ≈ (width * height) / 750
    return round((w * h) / 750)


TOOLS = [
    {
        "name": "tap",
        "description": "화면의 (x, y) 좌표를 한 번 탭한다. 좌표는 방금 받은 스크린샷 이미지 픽셀 기준.",
        "input_schema": {
            "type": "object",
            "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}},
            "required": ["x", "y"],
        },
    },
    {
        "name": "swipe",
        "description": "(x1,y1)에서 (x2,y2)로 스와이프한다. 좌표는 방금 받은 스크린샷 이미지 픽셀 기준.",
        "input_schema": {
            "type": "object",
            "properties": {
                "x1": {"type": "integer"},
                "y1": {"type": "integer"},
                "x2": {"type": "integer"},
                "y2": {"type": "integer"},
                "duration_ms": {"type": "integer", "default": 300},
            },
            "required": ["x1", "y1", "x2", "y2"],
        },
    },
    {
        "name": "type_text",
        "description": "현재 포커스된 입력창에 텍스트를 입력한다.",
        "input_schema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]},
    },
    {
        "name": "key_event",
        "description": "시스템 키를 누른다. keycode 예: BACK, HOME, ENTER, APP_SWITCH",
        "input_schema": {
            "type": "object",
            "properties": {"keycode": {"type": "string", "enum": ["BACK", "HOME", "ENTER", "APP_SWITCH", "DEL"]}},
            "required": ["keycode"],
        },
    },
    {
        "name": "wait",
        "description": "화면 전환 등을 기다리기 위해 지정한 초만큼 대기한다.",
        "input_schema": {"type": "object", "properties": {"seconds": {"type": "number"}}, "required": ["seconds"]},
    },
    {
        "name": "finish",
        "description": "작업이 끝났거나(성공) 더 이상 진행할 수 없을 때(실패) 호출해서 루프를 종료한다.",
        "input_schema": {
            "type": "object",
            "properties": {"success": {"type": "boolean"}, "message": {"type": "string"}},
            "required": ["success", "message"],
        },
    },
]

SYSTEM_TEXT = (
    "당신은 안드로이드 폰을 원격으로 조작하는 에이전트입니다.\n"
    f"작업: {TASK_DESCRIPTION}\n\n"
    f"{NAV_TIP}\n"
    "아래는 현재 화면 스크린샷입니다. 필요한 tool을 순서대로 호출해서 "
    "작업을 수행하세요. 작업이 끝나면 반드시 finish tool을 호출하세요."
)


def execute_tool(name, inp, scale):
    if name == "tap":
        x, y = int(inp["x"] / scale), int(inp["y"] / scale)
        adb("shell", "input", "tap", str(x), str(y))
        return f"tap({x},{y}) 실행함"
    if name == "swipe":
        x1, y1 = int(inp["x1"] / scale), int(inp["y1"] / scale)
        x2, y2 = int(inp["x2"] / scale), int(inp["y2"] / scale)
        dur = str(inp.get("duration_ms", 300))
        adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), dur)
        return f"swipe({x1},{y1})->({x2},{y2}) 실행함"
    if name == "type_text":
        type_text_via_adb_keyboard(inp["text"])
        return f"텍스트 입력함: {inp['text']}"
    if name == "key_event":
        adb("shell", "input", "keyevent", inp["keycode"])
        return f"키 입력함: {inp['keycode']}"
    if name == "wait":
        time.sleep(float(inp["seconds"]))
        return f"{inp['seconds']}초 대기함"
    raise ValueError(f"알 수 없는 tool: {name}")


def _g(block, key, default=None):
    """dict든 anthropic SDK의 pydantic 블록 객체든 동일하게 필드를 읽는다."""
    if isinstance(block, dict):
        return block.get(key, default)
    return getattr(block, key, default)


def summarize_messages_for_log(messages):
    """messages 리스트를 순회하며 사람이 읽을 요약(텍스트는 전문, 이미지는 크기만) 반환."""
    lines = []
    total_chars = 0
    image_count = 0
    for turn in messages:
        role = turn["role"]
        content = turn["content"]
        if isinstance(content, str):
            lines.append(f"  [{role}] text: {content!r}")
            total_chars += len(content)
            continue
        for block in content:
            btype = _g(block, "type")
            if btype == "text":
                text = _g(block, "text")
                lines.append(f"  [{role}][text] {text!r}")
                total_chars += len(text)
            elif btype == "thinking":
                thinking = _g(block, "thinking") or ""
                lines.append(f"  [{role}][thinking] ({len(thinking)} chars) {thinking[:200]!r}...")
                total_chars += len(thinking)
            elif btype == "image":
                image_count += 1
                data_len = len(_g(_g(block, "source"), "data"))
                lines.append(f"  [{role}][image] base64 {data_len} bytes (원본 png)")
                total_chars += data_len
            elif btype == "tool_use":
                inp = _g(block, "input")
                s = json.dumps(inp, ensure_ascii=False)
                lines.append(f"  [{role}][tool_use] {_g(block, 'name')}({s})")
                total_chars += len(s)
            elif btype == "tool_result":
                c = _g(block, "content")
                if isinstance(c, str):
                    lines.append(f"  [{role}][tool_result] {c!r}")
                    total_chars += len(c)
                else:
                    for sub in c:
                        if _g(sub, "type") == "text":
                            t = _g(sub, "text")
                            lines.append(f"  [{role}][tool_result][text] {t!r}")
                            total_chars += len(t)
                        elif _g(sub, "type") == "image":
                            image_count += 1
                            data_len = len(_g(_g(sub, "source"), "data"))
                            lines.append(f"  [{role}][tool_result][image] base64 {data_len} bytes (원본 png)")
                            total_chars += data_len
            else:
                lines.append(f"  [{role}][{btype}] (알 수 없는 블록 타입, 원본: {block!r:.200})")
    return lines, total_chars, image_count


def run():
    api_key = Path(API_KEY_PATH).read_text(encoding="utf-8").strip()
    client = Anthropic(api_key=api_key)
    tracker = UsageTracker()

    log("[준비] 유튜브+김비서 완전 종료 후, 김비서만 재실행...")
    force_stop_and_start_from_kimbisiseo_youtube()

    w, h = get_screen_size()
    log(f"[화면 해상도] {w}x{h}")

    png, scale, (rw, rh) = resize_for_model(take_screenshot())
    img_blk, b64_len, png_len = image_block(png)
    est_tok = est_image_tokens(rw, rh)

    messages = [
        {
            "role": "user",
            "content": [
                {"type": "text", "text": SYSTEM_TEXT},
                img_blk,
            ],
        }
    ]

    log("=" * 70)
    log("[고정 비용] 이 앱이 매 스텝 API 요청마다 반드시 함께 보내는 것들")
    log("=" * 70)
    log(f"- tool 스키마(TOOLS) JSON 직렬화 크기: {len(json.dumps(TOOLS, ensure_ascii=False))} chars (도구 {len(TOOLS)}개)")
    log(f"- 최초 지시문(SYSTEM_TEXT) 길이: {len(SYSTEM_TEXT)} chars")
    log(f"- 리사이즈된 스크린샷 1장: {rw}x{rh}px, base64 {b64_len} bytes, 예상 이미지 토큰 ≈ {est_tok}")
    log("(주의: Anthropic Messages API는 서버가 대화를 기억하지 않으므로, 매 스텝마다 지금까지의")
    log(" messages 배열 '전체'를 다시 통으로 보낸다 — 즉 이전 스텝의 스크린샷들도 전부 누적돼서 재전송됨)")
    log("")

    finished_ok = False
    crashed_error = None
    try:
        for step in range(1, MAX_STEPS + 1):
            tracker.steps = step
            log("\n" + "=" * 25 + f" STEP {step} " + "=" * 25)

            lines, total_chars, image_count = summarize_messages_for_log(messages)
            log(f"--- [요청/입력] messages 배열 턴 수: {len(messages)}, 누적 이미지 수: {image_count}, 텍스트 총량(문자수, base64 포함): {total_chars} ---")
            for l in lines:
                log(l)

            resp = client.messages.create(model=MODEL, max_tokens=1024, tools=TOOLS, messages=messages)
            tracker.record_api_call(resp.usage)
            u = resp.usage
            log(
                f"--- [실제 사용량] input_tokens={u.input_tokens}, output_tokens={u.output_tokens}, "
                f"cache_creation={getattr(u, 'cache_creation_input_tokens', 0)}, "
                f"cache_read={getattr(u, 'cache_read_input_tokens', 0)} ---"
            )

            messages.append({"role": "assistant", "content": resp.content})

            log("--- [응답/출력] ---")
            for b in resp.content:
                if b.type == "text" and b.text.strip():
                    log(f"  [assistant][text] {b.text.strip()!r}")
                elif b.type == "tool_use":
                    log(f"  [assistant][tool_use] {b.name}({json.dumps(b.input, ensure_ascii=False)})")

            tool_use_blocks = [b for b in resp.content if b.type == "tool_use"]
            if not tool_use_blocks:
                log("[경고] tool 호출 없이 응답 종료됨")
                break

            tool_results = []
            finished = False
            for block in tool_use_blocks:
                tracker.record_tool_call(block.name, block.input)
                if block.name == "finish":
                    finished_ok = bool(block.input.get("success"))
                    log(f"[종료] {'성공' if finished_ok else '실패'}: {block.input.get('message')}")
                    finished = True
                    tool_results.append({"type": "tool_result", "tool_use_id": block.id, "content": "작업 종료됨"})
                    continue

                try:
                    result_text = execute_tool(block.name, block.input, scale)
                    time.sleep(0.8)
                    new_png, scale, (nw, nh) = resize_for_model(take_screenshot())
                    new_blk, _, _ = image_block(new_png)
                    tool_results.append(
                        {
                            "type": "tool_result",
                            "tool_use_id": block.id,
                            "content": [{"type": "text", "text": result_text + " / 현재 화면:"}, new_blk],
                        }
                    )
                except Exception as e:
                    log(f"[도구 실행 오류] {block.name}({block.input}) -> {e}")
                    tool_results.append(
                        {
                            "type": "tool_result",
                            "tool_use_id": block.id,
                            "content": f"도구 실행 실패: {e}. 인자를 다시 확인해서 재시도하세요.",
                            "is_error": True,
                        }
                    )

            messages.append({"role": "user", "content": tool_results})
            if finished:
                break
        else:
            log("\n[경고] 최대 스텝 수 도달, 강제 종료")
    except Exception as e:
        crashed_error = str(e)
        log(f"[예외 발생, 실험 중단] {e}")
    finally:
        restore_original_ime()
        tracker.finish()

    summary = tracker.summary_dict("io_log_youtube")
    summary["task_success"] = finished_ok
    if crashed_error:
        summary["crashed_error"] = crashed_error
    RESULT_PATH.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    LOG_PATH.write_text("\n".join(_log_lines), encoding="utf-8")
    log(f"\n[결과 저장] {RESULT_PATH}")
    log(f"[입출력 로그 저장] {LOG_PATH}")
    log(json.dumps({k: v for k, v in summary.items() if k not in ("tool_calls", "per_step")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    run()
