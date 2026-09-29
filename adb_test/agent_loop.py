# -*- coding: utf-8 -*-
"""
ADB + Claude API 를 이용한 안드로이드 화면 제어 테스트 루프.

Claude가 스크린샷을 보고 tap/swipe/text/key 같은 행동을 tool_use 로 응답하면,
그 행동을 adb 명령으로 실제 폰에 실행하고, 다시 스크린샷을 찍어 결과를 보여주는
과정을 반복한다. Claude Desktop 모바일 버전을 만들기 전, "Claude가 화면을 보고
올바르게 폰을 조작할 수 있는가"를 검증하기 위한 프로토타입.
"""

import base64
import io
import os
import re
import subprocess
import sys
import time
from pathlib import Path

from anthropic import Anthropic
from PIL import Image

sys.stdout.reconfigure(encoding="utf-8")

ADB_PATH = os.environ.get("ADB_PATH", "adb")
API_KEY_PATH = os.environ["ANTHROPIC_API_KEY_FILE"]  # API 키가 저장된 텍스트 파일 경로
MODEL = "claude-sonnet-5"
MAX_STEPS = 15
RESIZE_MAX_DIM = 1024  # Claude에 보낼 이미지 최대 변 길이

ADB_KEYBOARD_IME = "com.android.adbkeyboard/.AdbIME"


def adb(*args, timeout=15):
    result = subprocess.run(
        [ADB_PATH, *args], capture_output=True, timeout=timeout
    )
    return result.stdout.decode("utf-8", errors="replace").strip()


def get_screen_size():
    out = adb("shell", "wm", "size")
    m = re.search(r"(\d+)x(\d+)", out)
    if not m:
        raise RuntimeError(f"화면 크기를 읽을 수 없음: {out}")
    return int(m.group(1)), int(m.group(2))


def take_screenshot():
    result = subprocess.run(
        [ADB_PATH, "exec-out", "screencap", "-p"],
        capture_output=True,
        timeout=15,
    )
    return result.stdout  # PNG bytes


def resize_for_model(png_bytes):
    """모델에 보낼 이미지를 축소하고, 실제 좌표 환산에 쓸 스케일 비율을 반환."""
    img = Image.open(io.BytesIO(png_bytes)).convert("RGB")
    w, h = img.size
    scale = RESIZE_MAX_DIM / max(w, h)
    if scale < 1:
        img = img.resize((int(w * scale), int(h * scale)))
    else:
        scale = 1.0
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue(), scale


def image_block(png_bytes):
    b64 = base64.b64encode(png_bytes).decode("ascii")
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": "image/png", "data": b64},
    }


TOOLS = [
    {
        "name": "tap",
        "description": "화면의 (x, y) 좌표를 한 번 탭한다. 좌표는 방금 받은 스크린샷 이미지 픽셀 기준.",
        "input_schema": {
            "type": "object",
            "properties": {
                "x": {"type": "integer"},
                "y": {"type": "integer"},
            },
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
        "input_schema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "key_event",
        "description": "시스템 키를 누른다. keycode 예: BACK, HOME, ENTER, APP_SWITCH",
        "input_schema": {
            "type": "object",
            "properties": {
                "keycode": {
                    "type": "string",
                    "enum": ["BACK", "HOME", "ENTER", "APP_SWITCH", "DEL"],
                }
            },
            "required": ["keycode"],
        },
    },
    {
        "name": "wait",
        "description": "화면 전환 등을 기다리기 위해 지정한 초만큼 대기한다.",
        "input_schema": {
            "type": "object",
            "properties": {"seconds": {"type": "number"}},
            "required": ["seconds"],
        },
    },
    {
        "name": "finish",
        "description": "작업이 끝났거나(성공) 더 이상 진행할 수 없을 때(실패) 호출해서 루프를 종료한다.",
        "input_schema": {
            "type": "object",
            "properties": {
                "success": {"type": "boolean"},
                "message": {"type": "string"},
            },
            "required": ["success", "message"],
        },
    },
]


_original_ime = None
_adb_keyboard_active = False


def get_current_ime():
    return adb("shell", "settings", "get", "secure", "default_input_method")


def ensure_adb_keyboard():
    """한글 등 유니코드 입력을 위해 ADBKeyboard로 전환 (adb의 input text는 한글을 못 씀)."""
    global _original_ime, _adb_keyboard_active
    if _adb_keyboard_active:
        return
    if _original_ime is None:
        _original_ime = get_current_ime()
    adb("shell", "ime", "enable", ADB_KEYBOARD_IME)
    adb("shell", "ime", "set", ADB_KEYBOARD_IME)
    time.sleep(0.3)
    _adb_keyboard_active = True


def restore_original_ime():
    global _adb_keyboard_active
    if _original_ime and _adb_keyboard_active:
        adb("shell", "ime", "set", _original_ime)
        adb("shell", "ime", "disable", ADB_KEYBOARD_IME)
        _adb_keyboard_active = False


def execute_tool(name, inp, scale):
    """모델이 준 좌표(축소 이미지 기준)를 실제 화면 좌표로 환산해 adb로 실행."""
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
        ensure_adb_keyboard()
        b64 = base64.b64encode(inp["text"].encode("utf-8")).decode("ascii")
        adb("shell", "am", "broadcast", "-a", "ADB_INPUT_B64", "--es", "msg", b64)
        return f"텍스트 입력함: {inp['text']}"
    if name == "key_event":
        adb("shell", "input", "keyevent", inp["keycode"])
        return f"키 입력함: {inp['keycode']}"
    if name == "wait":
        time.sleep(float(inp["seconds"]))
        return f"{inp['seconds']}초 대기함"
    raise ValueError(f"알 수 없는 tool: {name}")


def run_task(task_description: str):
    api_key = Path(API_KEY_PATH).read_text(encoding="utf-8").strip()
    client = Anthropic(api_key=api_key)

    w, h = get_screen_size()
    print(f"[화면 해상도] {w}x{h}")

    png, scale = resize_for_model(take_screenshot())
    messages = [
        {
            "role": "user",
            "content": [
                {
                    "type": "text",
                    "text": (
                        f"당신은 안드로이드 폰을 원격으로 조작하는 에이전트입니다.\n"
                        f"작업: {task_description}\n\n"
                        f"아래는 현재 화면 스크린샷입니다. 필요한 tool을 순서대로 호출해서 "
                        f"작업을 수행하세요. 작업이 끝나면 반드시 finish tool을 호출하세요."
                    ),
                },
                image_block(png),
            ],
        }
    ]

    try:
        _run_steps(client, messages, scale)
    finally:
        restore_original_ime()


def _run_steps(client, messages, scale):
    for step in range(1, MAX_STEPS + 1):
        print(f"\n--- Step {step} ---")
        resp = client.messages.create(
            model=MODEL,
            max_tokens=1024,
            tools=TOOLS,
            messages=messages,
        )
        messages.append({"role": "assistant", "content": resp.content})

        tool_use_blocks = [b for b in resp.content if b.type == "tool_use"]
        text_blocks = [b for b in resp.content if b.type == "text"]
        for t in text_blocks:
            if t.text.strip():
                print(f"[Claude] {t.text.strip()}")

        if not tool_use_blocks:
            print("[경고] tool 호출 없이 응답 종료됨")
            break

        tool_results = []
        finished = False
        for block in tool_use_blocks:
            print(f"[실행] {block.name}({block.input})")
            if block.name == "finish":
                status = "성공" if block.input.get("success") else "실패"
                print(f"[종료] {status}: {block.input.get('message')}")
                finished = True
                tool_results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": "작업 종료됨",
                    }
                )
                continue

            result_text = execute_tool(block.name, block.input, scale)
            time.sleep(0.8)  # 화면 전환 대기
            new_png, scale = resize_for_model(take_screenshot())
            tool_results.append(
                {
                    "type": "tool_result",
                    "tool_use_id": block.id,
                    "content": [
                        {"type": "text", "text": result_text + " / 현재 화면:"},
                        image_block(new_png),
                    ],
                }
            )

        messages.append({"role": "user", "content": tool_results})

        if finished:
            break
    else:
        print("\n[경고] 최대 스텝 수 도달, 강제 종료")


if __name__ == "__main__":
    task = sys.argv[1] if len(sys.argv) > 1 else "설정 앱을 열고 Wi-Fi 화면까지 들어가기"
    run_task(task)
