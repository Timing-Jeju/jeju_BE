#!/bin/sh
set -eu

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"
SETUP_VALIDATION=false
CI_MODE=false
for arg in "$@"; do
  case "$arg" in
    --setup-validation) SETUP_VALIDATION=true ;;
    --ci) CI_MODE=true ;;
    *) echo "알 수 없는 옵션: $arg" >&2; exit 2 ;;
  esac
done

stage() {
  echo "[품질 게이트] $1"
}

BRANCH=${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-$(git branch --show-current)}}
SHA=$(git rev-parse HEAD 2>/dev/null || printf 'UNBORN')

stage "Git 상태와 브랜치 검사"
git status --short
if [ "$SETUP_VALIDATION" = false ]; then
  if [ "$CI_MODE" = true ] && { [ "$BRANCH" = main ] || [ "$BRANCH" = develop ]; }; then
    python3 scripts/git-hooks/validate-branch.py --allow-protected "$BRANCH"
  else
    python3 scripts/git-hooks/validate-branch.py "$BRANCH"
  fi
  if [ -n "$(git status --porcelain)" ]; then
    echo "작업 트리가 깨끗하지 않습니다." >&2
    exit 1
  fi
else
  echo "초기 설정 검증 모드: 보호 브랜치·미추적 설정 파일을 허용합니다."
fi

stage "비밀정보 검사"
python3 scripts/git-hooks/scan-staged-secrets.py --all-files

stage "포맷 검사"
./gradlew --no-daemon spotlessCheck
stage "컴파일"
./gradlew --no-daemon classes testClasses
stage "단위 테스트"
./gradlew --no-daemon unitTest
stage "Slice 테스트"
./gradlew --no-daemon sliceTest
stage "통합 테스트"
./gradlew --no-daemon integrationTest
stage "Architecture 테스트"
./gradlew --no-daemon architectureTest
stage "전체 테스트와 커버리지"
./gradlew --no-daemon test jacocoTestReport jacocoTestCoverageVerification
stage "애플리케이션 빌드"
./gradlew --no-daemon bootJar
stage "Docker 이미지·Compose·Health Check"
./scripts/docker-smoke-test.sh

if [ "$SETUP_VALIDATION" = false ] && [ "$CI_MODE" = false ] && [ "$SHA" != "UNBORN" ]; then
  SAFE_BRANCH=$(printf '%s' "$BRANCH" | sed 's#[^A-Za-z0-9._-]#__#g')
  STATE_DIR=".codex/state/quality-gates"
  mkdir -p "$STATE_DIR"
  python3 - "$STATE_DIR/$SAFE_BRANCH.json" "$BRANCH" "$SHA" <<'PY'
import datetime
import json
import sys
from pathlib import Path

path, branch, sha = sys.argv[1:]
payload = {
    "branch": branch,
    "headSha": sha,
    "checkedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "gradleCheck": "SUCCESS",
    "architectureTest": "SUCCESS",
    "coverageCheck": "SUCCESS",
    "dockerBuild": "SUCCESS",
    "dockerSmokeTest": "SUCCESS",
    "result": "SUCCESS",
}
Path(path).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
  echo "품질 게이트 상태 기록: $STATE_DIR/$SAFE_BRANCH.json"
fi

echo "[품질 게이트] 모든 단계 성공"
