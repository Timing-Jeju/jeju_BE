#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


BLOCKED_NAMES = {
    ".env",
    ".env.local",
    ".env.production",
    "id_rsa",
}
BLOCKED_SUFFIXES = (".pem", ".key")
PLACEHOLDER_RE = re.compile(
    r"(?i)(sk-example|your[_-]?(?:api[_-]?)?key|your[_-]?db[_-]?password|test-only-password|<secret>|dummy[-_]?token)"
)
ENV_PLACEHOLDER_RE = re.compile(r"\$\{[A-Z0-9_]+(?::([^}\r\n]*))?\}")
SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    re.compile(r"\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\b"),
    re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._~+/-]{24,}={0,2}\b"),
    re.compile(
        r"(?i)(?:secret|password|api[_-]?key|access[_-]?token)[\t ]*[:=][\t ]*['\"]?[^\s'\"]{16,}"
    ),
)


def blocked_path(path_text: str) -> bool:
    path = Path(path_text)
    name = path.name
    if name == ".env.example":
        return False
    if name in BLOCKED_NAMES or name.startswith("service-account") and name.endswith(".json"):
        return True
    return any(name.endswith(suffix) for suffix in BLOCKED_SUFFIXES)


def contains_secret(text: str) -> bool:
    sanitized = ENV_PLACEHOLDER_RE.sub(
        lambda match: match.group(1) or "PLACEHOLDER", text
    )
    sanitized = PLACEHOLDER_RE.sub("PLACEHOLDER", sanitized)
    return any(pattern.search(sanitized) for pattern in SECRET_PATTERNS)


def staged_files() -> list[str]:
    result = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"],
        text=True,
        capture_output=True,
        check=False,
    )
    return [line for line in result.stdout.splitlines() if line]


def tracked_files() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files"], text=True, capture_output=True, check=False
    )
    return [line for line in result.stdout.splitlines() if line]


def staged_content(path: str) -> str:
    result = subprocess.run(
        ["git", "show", f":{path}"], text=True, capture_output=True, errors="ignore", check=False
    )
    return result.stdout if result.returncode == 0 else ""


def main() -> int:
    parser = argparse.ArgumentParser(description="staged 비밀정보 검사")
    parser.add_argument("--check-path", action="append", default=[])
    parser.add_argument("--text-file", action="append", type=Path, default=[])
    parser.add_argument("--text", action="append", default=[])
    parser.add_argument("--all-files", action="store_true")
    args = parser.parse_args()
    failures: list[str] = []
    paths = args.check_path or (tracked_files() if args.all_files else staged_files())
    for path in paths:
        if blocked_path(path):
            failures.append(f"커밋 금지 파일: {path}")
        elif args.all_files and Path(path).is_file() and contains_secret(
            Path(path).read_text(encoding="utf-8", errors="ignore")
        ):
            failures.append(f"비밀정보 의심 값: {path}")
        elif not args.check_path and not args.all_files and contains_secret(staged_content(path)):
            failures.append(f"비밀정보 의심 값: {path}")
    for text_file in args.text_file:
        if contains_secret(text_file.read_text(encoding="utf-8", errors="ignore")):
            failures.append(f"비밀정보 의심 fixture: {text_file}")
    for index, text in enumerate(args.text, start=1):
        if contains_secret(text):
            failures.append(f"비밀정보 의심 입력: #{index}")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("비밀정보 검사 통과")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
