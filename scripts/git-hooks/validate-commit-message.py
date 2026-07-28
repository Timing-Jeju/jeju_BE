#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


BRANCH_RE = re.compile(
    r"^(feat|fix|build|chore|docs|style|refactor|test|release)/([1-9][0-9]*)-[a-z0-9]+(?:-[a-z0-9]+)*$"
)
MESSAGE_RE = re.compile(
    r"^(feat|fix|build|chore|docs|style|refactor|test|release): #([1-9][0-9]*) .+$"
)


def current_branch() -> str:
    result = subprocess.run(
        ["git", "branch", "--show-current"], text=True, capture_output=True, check=False
    )
    return result.stdout.strip()


def validate(message: str, branch: str) -> tuple[bool, str]:
    first_line = message.splitlines()[0].strip() if message.splitlines() else ""
    if first_line.startswith("Merge ") or first_line.startswith("Revert \""):
        return True, "Git 생성 Merge/Revert 커밋 예외를 허용합니다."
    branch_match = BRANCH_RE.fullmatch(branch)
    if not branch_match:
        return False, "현재 브랜치에서 Issue 번호를 확인할 수 없습니다. 브랜치 이름을 먼저 수정하세요."
    message_match = MESSAGE_RE.fullmatch(first_line)
    if not message_match:
        return False, (
            "커밋 메시지는 `type: #이슈번호 요약` 형식이어야 합니다. "
            "예: `test: #14 관광지 검색 실패 테스트 추가`"
        )
    if message_match.group(2) != branch_match.group(2):
        return False, (
            f"커밋 Issue #{message_match.group(2)}가 브랜치 Issue #{branch_match.group(2)}와 다릅니다."
        )
    return True, "커밋 메시지가 유효합니다."


def main() -> int:
    parser = argparse.ArgumentParser(description="Timing Jeju 커밋 메시지 검증")
    parser.add_argument("message_file", nargs="?", type=Path)
    parser.add_argument("--message")
    parser.add_argument("--branch")
    args = parser.parse_args()
    if args.message is not None:
        message = args.message
    elif args.message_file:
        message = args.message_file.read_text(encoding="utf-8")
    else:
        print("커밋 메시지 파일 또는 --message가 필요합니다.", file=sys.stderr)
        return 2
    ok, reason = validate(message, args.branch or current_branch())
    print(reason, file=sys.stdout if ok else sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
