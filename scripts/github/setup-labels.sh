#!/bin/sh
set -eu

MODE=${REMOTE_SETUP_MODE:-dry-run}
REPO=${1:-}
if [ -z "$REPO" ] && [ "$MODE" = apply ]; then
  REPO=$(gh repo view --json nameWithOwner --jq .nameWithOwner)
fi

apply_label() {
  name=$1 color=$2 description=$3
  if [ "$MODE" = dry-run ]; then
    printf 'gh label create %s --color %s --description "%s" --force\n' "$name" "$color" "$description"
  else
    gh label create "$name" --repo "$REPO" --color "$color" --description "$description" --force
  fi
}

apply_label type:feat 1f883d "새 기능"
apply_label type:fix d1242f "버그 수정"
apply_label type:build 8250df "빌드 또는 의존성"
apply_label type:chore 6e7781 "유지보수"
apply_label type:docs 0969da "문서"
apply_label type:style bf8700 "동작 없는 스타일 변경"
apply_label type:refactor fb8f44 "리팩터링"
apply_label type:test 0a7c72 "테스트"
apply_label type:release a40e26 "출시"
apply_label priority:P0 b60205 "즉시 대응"
apply_label priority:P1 d93f0b "높은 우선순위"
apply_label priority:P2 fbca04 "보통 우선순위"
apply_label priority:P3 c2e0c6 "낮은 우선순위"
apply_label status:ready 0e8a16 "개발 준비 완료"
apply_label status:in-progress 1d76db "진행 중"
apply_label status:blocked b60205 "차단됨"
apply_label status:needs-review 5319e7 "리뷰 필요"
apply_label status:changes-requested d93f0b "수정 요청"
apply_label status:approved 0e8a16 "승인됨"
