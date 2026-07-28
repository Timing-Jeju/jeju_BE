#!/bin/sh
set -eu

ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
BRANCH=$(git branch --show-current)
TYPE=${BRANCH%%/*}
REST=${BRANCH#*/}
ISSUE=${REST%%-*}
BASE=""
TITLE=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --base) BASE=$2; shift 2 ;;
    --title) TITLE=$2; shift 2 ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$BASE" ]; then
  [ "$TYPE" = "release" ] && BASE=main || BASE=develop
fi

command -v gh >/dev/null || { echo "GitHub CLI가 필요합니다." >&2; exit 1; }
gh auth status >/dev/null
python3 scripts/git-hooks/validate-pr-ready.py --base "$BASE"

if [ -z "$TITLE" ]; then
  case "$TYPE" in
    feat) PREFIX=Feat ;;
    fix) PREFIX=Fix ;;
    build) PREFIX=Build ;;
    chore) PREFIX=Chore ;;
    docs) PREFIX=Docs ;;
    style) PREFIX=Style ;;
    refactor) PREFIX=Refactor ;;
    test) PREFIX=Test ;;
    release) PREFIX=Release ;;
    *) echo "지원하지 않는 브랜치 type입니다." >&2; exit 1 ;;
  esac
  TITLE="[$PREFIX] #$ISSUE 작업 변경사항"
fi

BODY_FILE=$(mktemp)
cleanup() { rm -f "$BODY_FILE"; }
trap cleanup EXIT INT TERM
{
  printf '# 관련 Issue\n\nCloses #%s\n\n' "$ISSUE"
  printf '# 변경 목적\n\nIssue #%s의 요구사항을 반영합니다.\n\n' "$ISSUE"
  printf '# 검증 결과\n\n- 최신 HEAD 품질 게이트: SUCCESS\n- PR 전 Reviewer: APPROVED\n- 자동 머지: 사용하지 않음\n'
} > "$BODY_FILE"

URL=$(gh pr create --base "$BASE" --head "$BRANCH" --title "$TITLE" --body-file "$BODY_FILE")
NUMBER=${URL##*/}
printf 'PR_RESULT: CREATED\nPR_NUMBER: %s\nPR_URL: %s\nBASE_BRANCH: %s\nHEAD_BRANCH: %s\nISSUE_NUMBER: %s\nQUALITY_GATE: SUCCESS\nREVIEW_GATE: APPROVED\nBLOCK_REASON:\n' \
  "$NUMBER" "$URL" "$BASE" "$BRANCH" "$ISSUE"
