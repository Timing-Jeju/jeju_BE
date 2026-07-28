#!/usr/bin/env python3
from __future__ import annotations

from hook_common import BRANCH_RE, PROTECTED_BRANCHES, allow, branch_issue, current_branch, repo_root, run


def main() -> None:
    root = repo_root()
    branch = current_branch(root)
    _, status, _ = run(["git", "status", "--short"], root)
    issue = branch_issue(branch)

    messages = [
        f"현재 브랜치: {branch or '확인 불가'}",
        f"연결된 Issue: #{issue}" if issue else "연결된 Issue: 없음",
        "작업 전 AGENTS.md와 현재 역할의 Skill을 읽으세요.",
        "운영 코드는 반드시 테스트의 Red 실패를 확인한 뒤 작성하세요.",
    ]
    if branch in PROTECTED_BRANCHES:
        messages.append("main/develop에서는 기능 개발이나 직접 커밋을 할 수 없습니다. Issue 브랜치를 먼저 만드세요.")
    elif branch and not BRANCH_RE.fullmatch(branch):
        messages.append("현재 브랜치 이름이 규칙에 맞지 않습니다.")
    messages.append("작업 트리: 깨끗함" if not status else "작업 트리: 변경사항 있음(파일명만 확인하고 비밀값은 출력하지 않음)")
    allow("\n".join(messages))


if __name__ == "__main__":
    main()
