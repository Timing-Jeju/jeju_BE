#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import subprocess
import sys


BRANCH_RE = re.compile(r"^(feat|fix|refactor)/[1-9][0-9]*-")


def staged_files() -> list[str]:
    result = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"],
        text=True,
        capture_output=True,
        check=False,
    )
    return [line for line in result.stdout.splitlines() if line]


def current_branch() -> str:
    result = subprocess.run(
        ["git", "branch", "--show-current"], text=True, capture_output=True, check=False
    )
    return result.stdout.strip()


def validate(branch: str, paths: list[str]) -> tuple[bool, str]:
    if not BRANCH_RE.match(branch):
        return True, "현재 브랜치 type에는 운영 코드/테스트 짝 검사가 적용되지 않습니다."
    production_changed = any(path.startswith("src/main/java/") and path.endswith(".java") for path in paths)
    test_changed = any(path.startswith("src/test/java/") and path.endswith(".java") for path in paths)
    if production_changed and not test_changed:
        return False, (
            "feat/fix/refactor 브랜치에서 운영 Java 코드가 변경됐지만 테스트 변경이 없습니다. "
            "테스트 제외가 필요하면 Issue와 Reviewer의 명시적 승인을 받으세요."
        )
    return True, "운영 코드와 테스트 변경 짝이 유효합니다."


def main() -> int:
    parser = argparse.ArgumentParser(description="운영 코드와 테스트 변경 짝 검증")
    parser.add_argument("--branch", default=None)
    parser.add_argument("paths", nargs="*")
    args = parser.parse_args()
    ok, reason = validate(args.branch or current_branch(), args.paths or staged_files())
    print(reason, file=sys.stdout if ok else sys.stderr)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
