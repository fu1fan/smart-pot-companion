#!/usr/bin/env python3
"""Run an Aliyun NLS TTS -> ASR smoke test without persisting credentials."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


TOKEN_HOST = "nls-meta.cn-shanghai.aliyuncs.com"
NLS_HOST = "nls-gateway-cn-shanghai.aliyuncs.com"
TEST_TEXT = "你好，我是桌面智能盆栽。"


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing environment variable: {name}")
    return value


def percent_encode(value: str) -> str:
    return quote(str(value), safe="~")


def create_token(access_key_id: str, access_key_secret: str) -> tuple[str, int]:
    params = {
        "AccessKeyId": access_key_id,
        "Action": "CreateToken",
        "Format": "JSON",
        "RegionId": "cn-shanghai",
        "SignatureMethod": "HMAC-SHA1",
        "SignatureNonce": str(uuid.uuid4()),
        "SignatureVersion": "1.0",
        "Timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "Version": "2019-02-28",
    }
    canonicalized_query = "&".join(
        f"{percent_encode(key)}={percent_encode(value)}"
        for key, value in sorted(params.items())
    )
    string_to_sign = f"GET&%2F&{percent_encode(canonicalized_query)}"
    signature = base64.b64encode(
        hmac.new(
            f"{access_key_secret}&".encode(),
            string_to_sign.encode(),
            hashlib.sha1,
        ).digest()
    ).decode()
    params["Signature"] = signature

    url = f"https://{TOKEN_HOST}/?{urlencode(params)}"
    with urlopen(url, timeout=20) as response:
        body = json.load(response)

    token = body["Token"]
    return token["Id"], int(token["ExpireTime"])


def synthesize(token: str, appkey: str, output_path: Path) -> None:
    params = {
        "appkey": appkey,
        "token": token,
        "text": TEST_TEXT,
        "format": "wav",
        "sample_rate": "16000",
        "voice": "xiaoyun",
    }
    url = f"https://{NLS_HOST}/stream/v1/tts?{urlencode(params)}"
    with urlopen(url, timeout=30) as response:
        audio = response.read()
        content_type = response.headers.get("Content-Type", "")

    if not audio.startswith(b"RIFF"):
        raise RuntimeError(
            f"TTS failed: content-type={content_type}, body={audio[:500]!r}"
        )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(audio)


def recognize(token: str, appkey: str, audio_path: Path) -> dict:
    params = {
        "appkey": appkey,
        "format": "wav",
        "sample_rate": "16000",
        "enable_punctuation_prediction": "true",
        "enable_inverse_text_normalization": "true",
        "enable_voice_detection": "true",
    }
    url = f"https://{NLS_HOST}/stream/v1/asr?{urlencode(params)}"
    request = Request(
        url,
        data=audio_path.read_bytes(),
        method="POST",
        headers={
            "Content-Type": "application/octet-stream",
            "X-NLS-Token": token,
        },
    )
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def main() -> int:
    access_key_id = require_env("ALIYUN_AK_ID")
    access_key_secret = require_env("ALIYUN_AK_SECRET")
    appkey = require_env("ALIYUN_NLS_APPKEY")
    output_path = Path("build") / "aliyun_nls_smoke" / "tts_test.wav"

    token, expire_time = create_token(access_key_id, access_key_secret)
    print(f"Token created; expires at Unix timestamp {expire_time}")

    synthesize(token, appkey, output_path)
    print(f"TTS succeeded: {output_path}")

    result = recognize(token, appkey, output_path)
    print("ASR response:", json.dumps(result, ensure_ascii=False))
    if result.get("status") != 20000000:
        raise RuntimeError("ASR request did not succeed")

    print("Aliyun NLS smoke test passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Aliyun NLS smoke test failed: {exc}", file=sys.stderr)
        raise
