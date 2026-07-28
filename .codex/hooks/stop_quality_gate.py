#!/usr/bin/env python3
from __future__ import annotations

import re

from hook_common import (
    BRANCH_RE,
    allow,
    block,
    current_branch,
    event_text,
    head_sha,
    is_dirty,
    load_json,
    read_input,
    repo_root,
    state_path,
)


COMPLETION_RE = re.compile(r"(?i)(구현 완료|완료|ready for review|PR 생성 가능|\bdone\b|\bfinished\b)")


def stop_reasons(root, branch: str, sha: str, dirty: bool) -> list[str]:
    reasons: list[str] = []
    if dirty:
        reasons.append("작업 트리가 깨끗하지 않습니다.")
    gate = load_json(state_path(root, "quality-gates", branch))
    if not gate or gate.get("headSha") != sha or gate.get("result") != "SUCCESS":
        reasons.append("현재 HEAD의 테스트·Docker 품질 게이트 성공 기록이 없습니다.")
    return reasons


def main() -> None:
    data = read_input()
    if data.get("stop_hook_active"):
        allow("Stop Hook 재진입이므로 추가 차단 없이 종료합니다.")
        return
    text = event_text(data)
    root = repo_root()
    branch = current_branch(root)
    if not COMPLETION_RE.search(text) or not BRANCH_RE.fullmatch(branch):
        allow()
        return
    reasons = stop_reasons(root, branch, head_sha(root), is_dirty(root))
    if "pr" in text.lower():
        review = load_json(state_path(root, "reviews", branch))
        if not review or review.get("headSha") != head_sha(root) or review.get("verdict") != "APPROVED":
            reasons.append("PR 생성 선언에 필요한 최신 Reviewer APPROVED 기록이 없습니다.")
    if reasons:
        block("완료 조건이 충족되지 않았습니다.\n- " + "\n- ".join(reasons))
    else:
        allow()


if __name__ == "__main__":
    main()
