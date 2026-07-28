#!/bin/sh
set -eu

MODE=${REMOTE_SETUP_MODE:-dry-run}
REPO=${1:-}
if [ "$MODE" = dry-run ]; then
  echo "[dry-run] main/develop Ruleset을 생성 또는 갱신합니다."
  echo "[dry-run] PR 필수, 승인 1명, stale 승인 취소, 대화 해결, quality-gate 통과, 삭제·force push 금지"
  echo "[dry-run] main 대상 PR의 head=develop 및 Release 제목은 CI의 release-policy Job이 검사합니다."
  echo "[dry-run] 자동 머지를 비활성화합니다."
  exit 0
fi

command -v gh >/dev/null || { echo "GitHub CLI가 필요합니다." >&2; exit 1; }
gh auth status >/dev/null
[ -n "$REPO" ] || REPO=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
case "$REPO" in */*) ;; *) echo "owner/repo 형식이 아닙니다." >&2; exit 1 ;; esac

apply_ruleset() {
  branch=$1
  name="protect-$branch"
  payload=$(mktemp)
  trap 'rm -f "$payload"' EXIT INT TERM
  python3 - "$payload" "$name" "$branch" <<'PY'
import json
import sys
from pathlib import Path

path, name, branch = sys.argv[1:]
payload = {
    "name": name,
    "target": "branch",
    "enforcement": "active",
    "conditions": {"ref_name": {"include": [f"refs/heads/{branch}"], "exclude": []}},
    "rules": [
        {"type": "deletion"},
        {"type": "non_fast_forward"},
        {"type": "pull_request", "parameters": {
            "required_approving_review_count": 1,
            "dismiss_stale_reviews_on_push": True,
            "require_code_owner_review": False,
            "require_last_push_approval": True,
            "required_review_thread_resolution": True,
        }},
        {"type": "required_status_checks", "parameters": {
            "strict_required_status_checks_policy": True,
            "do_not_enforce_on_create": False,
            "required_status_checks": [{"context": "quality-gate"}],
        }},
    ],
}
Path(path).write_text(json.dumps(payload), encoding="utf-8")
PY
  existing=$(gh api "repos/$REPO/rulesets" --jq ".[] | select(.name == \"$name\") | .id" | head -n 1)
  if [ -n "$existing" ]; then
    gh api --method PUT "repos/$REPO/rulesets/$existing" --input "$payload" >/dev/null
    echo "Ruleset 갱신: $name"
  else
    gh api --method POST "repos/$REPO/rulesets" --input "$payload" >/dev/null
    echo "Ruleset 생성: $name"
  fi
  rm -f "$payload"
  trap - EXIT INT TERM
}

apply_ruleset main
apply_ruleset develop
gh api --method PATCH "repos/$REPO" -F allow_auto_merge=false >/dev/null
echo "자동 머지 비활성화 완료"
