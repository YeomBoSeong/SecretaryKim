# -*- coding: utf-8 -*-
"""
[방식 B] 노드 트리(접근성 트리) 방식 에이전트 루프.
스크린샷 이미지 대신 `adb shell uiautomator dump` 로 얻은 UI 계층 구조를
텍스트로 요약해서 모델에 전달한다 (android AssistantAccessibilityService.dumpAccessibilityTree 의
adb 버전 포팅). 좌표는 각 요소의 실제 화면 중심 좌표를 그대로 사용하므로 스케일 환산이 없다.
"""

import json
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from anthropic import Anthropic

sys.stdout.reconfigure(encoding="utf-8")
sys.path.insert(0, str(Path(__file__).parent))
from common import (
    API_KEY_PATH,
    MODEL,
    TASK_DESCRIPTION,
    UsageTracker,
    adb,
    force_stop_and_relaunch,
    restore_original_ime,
    type_text_via_adb_keyboard,
)

MAX_STEPS = 30
MAX_ELEMENTS = 100
DUMP_PATH_ON_DEVICE = "/sdcard/window_dump.xml"

BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def dump_ui_tree_xml() -> str:
    adb("shell", "uiautomator", "dump", DUMP_PATH_ON_DEVICE)
    return adb("shell", "cat", DUMP_PATH_ON_DEVICE)


def _parse_bounds(s):
    m = BOUNDS_RE.match(s or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def extract_elements(xml_text: str, max_elements=MAX_ELEMENTS):
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []
    results = []

    def visit(node):
        if len(results) >= max_elements:
            return
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        label = text or desc
        clickable = node.get("clickable") == "true"
        long_clickable = node.get("long-clickable") == "true"
        checkable = node.get("checkable") == "true"
        cls = (node.get("class") or "View").rsplit(".", 1)[-1]
        editable = "EditText" in cls
        interactive = clickable or long_clickable or checkable or editable
        center = _parse_bounds(node.get("bounds"))
        if (label or interactive) and center is not None:
            resid = (node.get("resource-id") or "").rsplit("/", 1)[-1]
            results.append(
                {
                    "label": label[:60],
                    "cls": cls,
                    "resid": resid,
                    "clickable": clickable,
                    "editable": editable,
                    "center": center,
                }
            )
        for child in list(node):
            if len(results) >= max_elements:
                break
            visit(child)

    visit(root)
    return results


def format_elements(elements) -> str:
    if not elements:
        return "탭 가능한 요소를 찾지 못했어요."
    lines = []
    for i, e in enumerate(elements):
        tag = "[입력창]" if e["editable"] else ("[탭 가능]" if e["clickable"] else "[텍스트]")
        idtag = f" id={e['resid']}" if e["resid"] else ""
        lines.append(f"{i}: {tag} {e['cls']}{idtag} \"{e['label']}\" center=({e['center'][0]},{e['center'][1]})")
    return "\n".join(lines)


def current_tree_text() -> str:
    xml_text = dump_ui_tree_xml()
    elements = extract_elements(xml_text)
    return format_elements(elements)


TOOLS = [
    {
        "name": "tap",
        "description": "화면의 (x, y) 좌표를 한 번 탭한다. 좌표는 노드 트리 목록에 나온 center 좌표(실제 화면 픽셀)를 그대로 쓴다.",
        "input_schema": {
            "type": "object",
            "properties": {"x": {"type": "integer"}, "y": {"type": "integer"}},
            "required": ["x", "y"],
        },
    },
    {
        "name": "swipe",
        "description": "(x1,y1)에서 (x2,y2)로 스와이프한다. 좌표는 실제 화면 픽셀 기준 (목록 스크롤 등에 사용).",
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
        "description": "현재 포커스된 입력창([입력창] 태그)에 텍스트를 입력한다.",
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


def execute_tool(name, inp):
    if name == "tap":
        x, y = int(inp["x"]), int(inp["y"])
        adb("shell", "input", "tap", str(x), str(y))
        return f"tap({x},{y}) 실행함"
    if name == "swipe":
        x1, y1, x2, y2 = int(inp["x1"]), int(inp["y1"]), int(inp["x2"]), int(inp["y2"])
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

    print("[준비] 배달의민족 완전 종료 후 재실행...")
    force_stop_and_relaunch()

    tree_text = current_tree_text()
    messages = [
        {
            "role": "user",
            "content": (
                "당신은 안드로이드 폰을 원격으로 조작하는 에이전트입니다.\n"
                f"작업: {TASK_DESCRIPTION}\n\n"
                "화면 이미지 대신, 현재 화면의 UI 요소 목록(노드 트리)을 아래에 텍스트로 드립니다. "
                "각 줄은 'index: [태그] 클래스명 \"라벨\" center=(x,y)' 형식이며, tap 할 때는 이 center 좌표를 "
                "그대로 사용하세요. 필요한 tool을 순서대로 호출해서 작업을 수행하고, 끝나면 반드시 finish를 호출하세요.\n\n"
                f"[현재 화면 UI 요소 목록]\n{tree_text}"
            ),
        }
    ]

    finished_ok = False
    crashed_error = None
    try:
        for step in range(1, MAX_STEPS + 1):
            tracker.steps = step
            print(f"\n--- [노드 트리 방식] Step {step} ---")
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
                    result_text = execute_tool(block.name, block.input)
                    time.sleep(0.8)
                    new_tree_text = current_tree_text()
                    content = f"{result_text}\n\n[현재 화면 UI 요소 목록]\n{new_tree_text}"
                except Exception as e:
                    print(f"[도구 실행 오류] {block.name}({block.input}) -> {e}")
                    content = f"도구 실행 실패: {e}. 인자를 다시 확인해서 재시도하세요."
                tool_results.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": content,
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

    summary = tracker.summary_dict("node_tree")
    summary["task_success"] = finished_ok
    if crashed_error:
        summary["crashed_error"] = crashed_error
    out_path = Path(__file__).parent / "result_nodetree.json"
    out_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[결과 저장] {out_path}")
    print(json.dumps({k: v for k, v in summary.items() if k not in ("tool_calls", "per_step")}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    run()
