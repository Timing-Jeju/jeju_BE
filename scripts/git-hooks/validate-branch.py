#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import subprocess
import sys


PATTERN = re.compile(
    r"^(feat|fix|build|chore|docs|style|refactor|test|release)/[1-9][0-9]*-[a-z0-9]+(?:-[a-z0-9]+)*$"
)
PROTECTED = {"main", "develop"}


def current_branch() -> str:
    result = subprocess.run(
        ["git", "branch", "--show-current"], text=True, capture_output=True, check=False
    )
    return result.stdout.strip()


def validate(branch: str, allow_protected: bool = False) -> tuple[bool, str]:
    if branch in PROTECTED:
        if allow_protected:
            return True, f"보호 브랜치 `{branch}`를 읽기 전용 검증 대상으로 허용합니다."
        return False, f"보호 브랜치 `{branch}`에서 직접 작업할 수 없습니다. Issue 브랜치를 생성하세요."
    if not PATTERN.fullmatch(branch):
        return False, (
            "브랜치 이름은 `{type}/{issue-number}-{영문-kebab-case}` 형식이어야 합니다. "
            "예: `feat/14-place-search`"
        )
    return True, f"브랜치 이름이 유효합니다: {branch}"


def main() -> int:
    parser = argparse.ArgumentParser(description="Timing Jeju 브랜치 이름 검증")
    parser.add_argument("branch", nargs="?", default=None)
    parser.add_argument("--allow-protected", action="store_true")
    args = parser.parse_args()
    branch = args.branch or current_branch()
    ok, message = validate(branch, args.allow_protected)
    print(message, file=sys.stdout if ok else sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
