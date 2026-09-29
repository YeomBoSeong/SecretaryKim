# -*- coding: utf-8 -*-
"""
[방식 A, v2] 스크린샷 방식 에이전트 루프 — v1과 로직은 동일하고, 이번 실험 조건만 바꿨다:
배달의민족을 미리 켜두지 않고 김비서 화면에서 시작해서, 배민 앱을 직접 찾아 켜는 과정까지 과제에 포함한다.
(노드 트리 v2와 동일한 시작 조건/과제 설명으로 공정하게 비교하기 위한 베이스라인.)
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
    NAV_TIP,
    TASK_DESCRIPTION,
    UsageTracker,
    adb,
    force_stop_and_start_from_kimbisiseo,
    get_screen_size,
    restore_original_ime,
    type_text_via_adb_keyboard,
)

MAX_STEPS = 35
RESIZE_MAX_DIM = 1024


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
    return buf.getvalue(), scale


def image_block(png_bytes):
    b64 = base64.b64encode(png_bytes).decode("ascii")
    return {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": b64}}


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


def run():
    api_key = Path(API_KEY_PATH).read_text(encoding="utf-8").strip()
    client = Anthropic(api_key=api_key)
    tracker = UsageTracker()

    print("[준비] 배달의민족+김비서 완전 종료 후, 김비서만 재실행...")
    force_stop_and_start_from_kimbisiseo()

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
                        "당신은 안드로이드 폰을 원격으로 조작하는 에이전트입니다.\n"
                        f"작업: {TASK_DESCRIPTION}\n\n"
                        f"{NAV_TIP}\n"
                        "아래는 현재 화면 스크린샷입니다. 필요한 tool을 순서대로 호출해서 "
                        "작업을 수행하세요. 작업이 끝나면 반드시 finish tool을 호출하세요."
                    ),
                },
                image_block(png),
            ],
        }
    ]

    finished_ok = False
    crashed_error = None
    try:
        for step in range(1, MAX_STEPS + 1):
            tracker.steps = step
            print(f"\n--- [스크린샷 v2] Step {step} ---")
            resp = client.messages.create(model=MODEL, max_tokens=1024, tools=TOOLS, messages=messages)
            tracker.record_api_call(resp.usage)
            messages.append({"role": "assistant", "content": resp.content})

            tool_use_blocks = [b for b in resp.content if b.type == "tool_use"]
            for t in resp.content:
                if t.type == "text" and t.text.strip():
                    print(f"[Claude] {t.text.strip()}")

            if not tool_use_blocks:
                print("[경고] tool 호출 없이 응답 종료됨")
                break

            tool_results = []
            finished = False
            for block in tool_use_blocks:
                print(f"[실행] {block.name}({block.input})")
                tracker.record_tool_call(block.name, block.input)
                if block.name == "finish":
                    finished_ok = bool(block.input.get("success"))
                    print(f"[종료] {'성공' if finished_ok else '실패'}: {block.input.get('message')}")
                    finished = True
                    tool_results.append({"type": "tool_result", "tool_use_id": block.id, "content": "작업 종료됨"})
                    continue

                try:
                    result_text = execute_tool(block.name, block.input, scale)
                    time.sleep(0.8)
                    new_png, scale = resize_for_model(take_screenshot())
                    tool_results.append(
                        {
                            "type": "tool_result",
                            "tool_use_id": block.id,
                            "content": [{"type": "text", "text": result_text + " / 현재 화면:"}, image_block(new_png)],
                        }
                    )
                except Exception as e:
                    print(f"[도구 실행 오류] {block.name}({block.input}) -> {e}")
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
            print("\n[경고] 최대 스텝 수 도달, 강제 종료")
    except Exception as e:
        crashed_error = str(e)
        print(f"[예외 발생, 실험 중단] {e}")
    finally:
        restore_original_ime()
        tracker.finish()

    summary = tracker.summary_dict("screenshot_v2")
    summary["task_success"] = finished_ok
    if crashed_error:
        summary["crashed_error"] = crashed_error
    out_path = Path(__file__).parent / "result_screenshot_v2.json"
    out_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[결과 저장] {out_path}")
    print(json.dumps({k: v for k, v in summary.items() if k not in ("tool_calls", "per_step")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    run()
