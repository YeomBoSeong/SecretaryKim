# -*- coding: utf-8 -*-
"""
스크린샷 방식 vs 노드 트리 방식 실험에서 공통으로 쓰는 adb / IME 헬퍼.
"""

import base64
import os
import re
import subprocess
import time
from pathlib import Path

ADB_PATH = os.environ.get("ADB_PATH", "adb")
API_KEY_PATH = os.environ["ANTHROPIC_API_KEY_FILE"]  # API 키가 저장된 텍스트 파일 경로
MODEL = "claude-sonnet-5"
PACKAGE = "com.sampleapp"  # 배달의민족
KIMBISISEO_PACKAGE = "com.ybs.assistant"  # 김비서 자체 (오버레이로 화면 위에 떠 있을 수 있어 실험 중엔 꺼둔다)
ADB_KEYBOARD_IME = "com.android.adbkeyboard/.AdbIME"

# v2: 배달의민족을 미리 켜두지 않고, 김비서 화면에서 시작해서 앱을 직접 찾아 켜는 것까지 과제에 포함한다.
TASK_DESCRIPTION = (
    "사용자가 김비서 채팅 화면에서 방금 '배달의민족에서 백억커피를 검색해서 카라멜팝콘을 "
    "장바구니에 담아줘'라고 요청했습니다. 배달의민족 앱은 아직 실행되어 있지 않을 수 있습니다 — "
    "필요하면 먼저 화면을 조작해서 배달의민족 앱을 직접 찾아 실행한 뒤, 백억커피를 검색하고 "
    "카라멜팝콘 메뉴를 장바구니에 담아주세요. 장바구니에 담기가 완료되면 작업을 끝내세요."
)

NAV_TIP = (
    "참고: 앱을 직접 찾아야 한다면, 홈 화면에서 위로 스와이프하면 전체 앱 목록(앱 서랍)이 열리고, "
    "그 안의 검색창에 앱 이름을 입력하면 빠르게 찾을 수 있습니다."
)


def adb(*args, timeout=15):
    result = subprocess.run([ADB_PATH, *args], capture_output=True, timeout=timeout)
    return result.stdout.decode("utf-8", errors="replace").strip()


def get_screen_size():
    out = adb("shell", "wm", "size")
    m = re.search(r"(\d+)x(\d+)", out)
    return int(m.group(1)), int(m.group(2))


def force_stop_and_relaunch():
    """배달의민족과 김비서(오버레이 간섭 방지)를 완전히 종료(백그라운드 포함)한 뒤
    배달의민족만 다시 실행해서 동일한 초기 상태를 만든다. (v1 방식 — 배민을 미리 켜둠)"""
    adb("shell", "am", "force-stop", KIMBISISEO_PACKAGE)
    adb("shell", "am", "force-stop", PACKAGE)
    time.sleep(1.0)
    # 홈으로 보내서 이전 화면 잔상 없이 완전히 초기화
    adb("shell", "input", "keyevent", "HOME")
    time.sleep(0.5)
    adb("shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(3.5)  # 스플래시/로딩 대기


def force_stop_and_start_from_kimbisiseo():
    """배달의민족과 김비서를 완전히 종료(백그라운드 포함)한 뒤, 김비서만 다시 실행해서
    '사용자가 김비서에게 막 요청한 직후' 상태에서 시작한다 (v2 — 배민 앱을 직접 찾는 과정 포함)."""
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "force-stop", KIMBISISEO_PACKAGE)
    time.sleep(1.0)
    adb("shell", "input", "keyevent", "HOME")
    time.sleep(0.5)
    adb("shell", "monkey", "-p", KIMBISISEO_PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")
    time.sleep(2.5)


_original_ime = None
_adb_keyboard_active = False


def get_current_ime():
    return adb("shell", "settings", "get", "secure", "default_input_method")


def ensure_adb_keyboard():
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


def type_text_via_adb_keyboard(text: str):
    ensure_adb_keyboard()
    b64 = base64.b64encode(text.encode("utf-8")).decode("ascii")
    adb("shell", "am", "broadcast", "-a", "ADB_INPUT_B64", "--es", "msg", b64)


class UsageTracker:
    """단계별 토큰 사용량과 스텝 수를 누적한다."""

    def __init__(self):
        self.steps = 0
        self.api_calls = 0
        self.input_tokens = 0
        self.output_tokens = 0
        self.cache_creation_tokens = 0
        self.cache_read_tokens = 0
        self.per_step = []
        self.tool_calls_log = []
        self.start_time = time.time()
        self.end_time = None

    def record_api_call(self, usage):
        self.api_calls += 1
        it = getattr(usage, "input_tokens", 0) or 0
        ot = getattr(usage, "output_tokens", 0) or 0
        cc = getattr(usage, "cache_creation_input_tokens", 0) or 0
        cr = getattr(usage, "cache_read_input_tokens", 0) or 0
        self.input_tokens += it
        self.output_tokens += ot
        self.cache_creation_tokens += cc
        self.cache_read_tokens += cr
        self.per_step.append(
            {"input_tokens": it, "output_tokens": ot, "cache_creation": cc, "cache_read": cr}
        )

    def record_tool_call(self, name, inp):
        self.tool_calls_log.append({"name": name, "input": inp})

    def finish(self):
        self.end_time = time.time()

    def summary_dict(self, method_name: str):
        return {
            "method": method_name,
            "steps": self.steps,
            "api_calls": self.api_calls,
            "total_input_tokens": self.input_tokens,
            "total_output_tokens": self.output_tokens,
            "cache_creation_tokens": self.cache_creation_tokens,
            "cache_read_tokens": self.cache_read_tokens,
            "total_tokens": self.input_tokens + self.output_tokens,
            "elapsed_seconds": round((self.end_time or time.time()) - self.start_time, 1),
            "tool_calls": self.tool_calls_log,
            "per_step": self.per_step,
        }
