#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys


TITLE_RE = re.compile(r"^\[(Feat|Fix|Build|Chore|Docs|Style|Refactor|Test|Release)] #[1-9][0-9]* .+$")
ISSUE_RE = re.compile(r"(?:Closes|Fixes|Resolves)\s+#[1-9][0-9]*", re.IGNORECASE)


def main() -> int:
    parser = argparse.ArgumentParser(description="PR 제목과 Issue 연결 검증")
    parser.add_argument("--title", required=True)
    parser.add_argument("--body", default="")
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()
    errors = []
    if not TITLE_RE.fullmatch(args.title):
        errors.append("PR 제목은 `[Type] #IssueNumber Summary` 형식이어야 합니다.")
    if not ISSUE_RE.search(args.body):
        errors.append("PR 본문에 `Closes #번호` 형식의 Issue 연결이 필요합니다.")
    if args.base == "main" and (args.head != "develop" or not args.title.startswith("[Release]")):
        errors.append("main 대상 PR은 develop에서 오는 [Release] PR만 허용합니다.")
    if args.base == "develop" and args.head in {"main", "develop"}:
        errors.append("develop 대상 일반 PR은 작업 브랜치에서 와야 합니다.")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("PR 메타데이터 검증 통과")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
