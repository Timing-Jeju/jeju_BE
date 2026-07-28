#!/usr/bin/env python3
from __future__ import annotations

import re

from hook_common import allow, block, event_text, read_input


PLACEHOLDER_RE = re.compile(
    r"(?i)(sk-example|your[_-]?(?:api[_-]?)?key|\$\{[A-Z0-9_]+\}|<secret>|dummy[-_]?token|example[-_]?token)"
)
SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\b"),
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/-]{24,}={0,2}\b"),
    re.compile(r"(?i)(?:jwt[_-]?secret|db[_-]?password|database[_-]?password)\s*[:=]\s*['\"]?[^\s'\"]{16,}"),
    re.compile(
        r'"private_key"\s*:\s*"-----BEGIN PRIVATE ' + r'KEY-----'
    ),
)


def contains_secret(text: str) -> bool:
    sanitized = PLACEHOLDER_RE.sub("PLACEHOLDER", text)
    return any(pattern.search(sanitized) for pattern in SECRET_PATTERNS)


def main() -> None:
    text = event_text(read_input())
    if contains_secret(text):
        block(
            "실제 비밀정보로 보이는 값이 감지되어 프롬프트 전달을 차단했습니다. "
            "해당 값을 즉시 폐기·재발급하고 placeholder로 바꿔 다시 요청하세요. 값 자체는 로그에 남기지 않습니다."
        )
    else:
        allow()


if __name__ == "__main__":
    main()
