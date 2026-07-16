#!/usr/bin/env python3
"""Configure an ESP-IDF sdkconfig with a short-lived Aliyun NLS token."""

from __future__ import annotations

import re
from datetime import datetime
from pathlib import Path

from aliyun_nls_smoke_test import create_token, require_env


def quote_kconfig(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def set_kconfig(text: str, key: str, value: str) -> str:
    replacement = f"{key}={value}"
    pattern = re.compile(rf"^(?:{re.escape(key)}=.*|# {re.escape(key)} is not set)$", re.MULTILINE)
    if pattern.search(text):
        return pattern.sub(replacement, text)
    if not text.endswith("\n"):
        text += "\n"
    return text + replacement + "\n"


def main() -> None:
    access_key_id = require_env("ALIYUN_AK_ID")
    access_key_secret = require_env("ALIYUN_AK_SECRET")
    appkey = require_env("ALIYUN_NLS_APPKEY")
    token, expire_time = create_token(access_key_id, access_key_secret)

    sdkconfig_path = Path("sdkconfig")
    text = sdkconfig_path.read_text(encoding="utf-8")
    values = {
        "CONFIG_SMART_POT_ASR_ENABLE": "y",
        "CONFIG_SMART_POT_NLS_APPKEY": quote_kconfig(appkey),
        "CONFIG_SMART_POT_NLS_TOKEN": quote_kconfig(token),
        "CONFIG_SMART_POT_ASR_ENDPOINT": quote_kconfig(
            "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr"
        ),
        "CONFIG_SMART_POT_ASR_RECORD_MS": "4000",
        "CONFIG_SMART_POT_TTS_ENABLE": "y",
        "CONFIG_SMART_POT_TTS_ENDPOINT": quote_kconfig(
            "https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/tts"
        ),
    }
    for key, value in values.items():
        text = set_kconfig(text, key, value)

    sdkconfig_path.write_text(text, encoding="utf-8")
    expire_local = datetime.fromtimestamp(expire_time).astimezone()
    print(f"Configured Aliyun NLS short-lived token; expires at {expire_local.isoformat()}")


if __name__ == "__main__":
    main()
