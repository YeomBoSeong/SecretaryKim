# -*- coding: utf-8 -*-
"""
ADB + OpenAI Responses API 를 이용한 안드로이드 화면 제어 테스트 루프.
agent_loop.py(Claude 버전)와 동일한 adb 실행 로직을 재사용하고,
모델 호출 부분만 OpenAI Responses API로 교체한 비교 테스트용 스크립트.
"""

import base64
import json
import os
import sys
import time
from pathlib import Path

from openai import OpenAI

from agent_loop import (
    adb,
    ensure_adb_keyboard,
    execute_tool,
    get_screen_size,
    resize_for_model,
    restore_original_ime,
    take_screenshot,
)

sys.stdout.reconfigure(encoding="utf-8")

OPENAI_API_KEY_PATH = os.environ["OPENAI_API_KEY_FILE"]  # API 키가 저장된 텍스트 파일 경로
MODEL = "gpt-5.6"
MAX_STEPS = 15

TOOLS = [
    {
        "type": "function",
        "name": "tap",
        "description": "화면의 (x, y) 좌표를 한 번 탭한다. 좌표는 방금 받은 스크린샷 이미지 픽셀 기준.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer"},
                "y": {"type": "integer"},
            },
            "required": ["x", "y"],
        },
    },
    {
        "type": "function",
        "name": "swipe",
        "description": "(x1,y1)에서 (x2,y2)로 스와이프한다. 좌표는 방금 받은 스크린샷 이미지 픽셀 기준.",
        "parameters": {
            "type": "object",
            "properties": {
                "x1": {"type": "integer"},
                "y1": {"type": "integer"},
                "x2": {"type": "integer"},
                "y2": {"type": "integer"},
                "duration_ms": {"type": "integer"},
            },
            "required": ["x1", "y1", "x2", "y2"],
        },
    },
    {
        "type": "function",
        "name": "type_text",
        "description": "현재 포커스된 입력창에 텍스트를 입력한다.",
        "parameters": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "type": "function",
        "name": "key_event",
        "description": "시스템 키를 누른다. keycode 예: BACK, HOME, ENTER, APP_SWITCH",
        "parameters": {
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
        "type": "function",
        "name": "wait",
        "description": "화면 전환 등을 기다리기 위해 지정한 초만큼 대기한다.",
        "parameters": {
            "type": "object",
            "properties": {"seconds": {"type": "number"}},
            "required": ["seconds"],
        },
    },
    {
        "type": "function",
        "name": "finish",
        "description": "작업이 끝났거나(성공) 더 이상 진행할 수 없을 때(실패) 호출해서 루프를 종료한다.",
        "parameters": {
            "type": "object",
            "properties": {
                "success": {"type": "boolean"},
                "message": {"type": "string"},
            },
            "required": ["success", "message"],
        },
    },
]


def image_content(png_bytes):
    b64 = base64.b64encode(png_bytes).decode("ascii")
    return {"type": "input_image", "image_url": f"data:image/png;base64,{b64}"}


def screen_message(text, png_bytes):
    return {
        "role": "user",
        "content": [
            {"type": "input_text", "text": text},
            image_content(png_bytes),
        ],
    }


def run_task(task_description: str):
    api_key = Path(OPENAI_API_KEY_PATH).read_text(encoding="utf-8").strip()
    client = OpenAI(api_key=api_key)

    w, h = get_screen_size()
    print(f"[화면 해상도] {w}x{h}")

    png, scale = resize_for_model(take_screenshot())
    input_items = [
        screen_message(
            f"당신은 안드로이드 폰을 원격으로 조작하는 에이전트입니다.\n"
            f"작업: {task_description}\n\n"
            f"아래는 현재 화면 스크린샷입니다. 필요한 tool을 순서대로 호출해서 "
            f"작업을 수행하세요. 작업이 끝나면 반드시 finish tool을 호출하세요.",
            png,
        )
    ]

    try:
        _run_steps(client, input_items, scale)
    finally:
        restore_original_ime()


def _run_steps(client, input_items, scale):
    for step_num in range(1, MAX_STEPS + 1):
        print(f"\n--- Step {step_num} ---")
        response = client.responses.create(model=MODEL, input=input_items, tools=TOOLS)
        input_items.extend(response.output)

        function_calls = [item for item in response.output if item.type == "function_call"]
        for item in response.output:
            if item.type == "message":
                for block in item.content:
                    text = getattr(block, "text", None)
                    if text and text.strip():
                        print(f"[GPT] {text.strip()}")

        if not function_calls:
            print("[경고] tool 호출 없이 응답 종료됨")
            break

        finished = False
        for call in function_calls:
            args = json.loads(call.arguments)
            print(f"[실행] {call.name}({args})")

            if call.name == "finish":
                status = "성공" if args.get("success") else "실패"
                print(f"[종료] {status}: {args.get('message')}")
                finished = True
                input_items.append(
                    {
                        "type": "function_call_output",
                        "call_id": call.call_id,
                        "output": "작업 종료됨",
                    }
                )
                continue

            result_text = execute_tool(call.name, args, scale)
            time.sleep(0.8)
            new_png, scale = resize_for_model(take_screenshot())
            input_items.append(
                {
                    "type": "function_call_output",
                    "call_id": call.call_id,
                    "output": result_text,
                }
            )
            input_items.append(screen_message("현재 화면:", new_png))

        if finished:
            break
    else:
        print("\n[경고] 최대 스텝 수 도달, 강제 종료")


if __name__ == "__main__":
    task = sys.argv[1] if len(sys.argv) > 1 else "휴대폰으로 인터넷에 갤럭시를 검색해줘"
    run_task(task)
