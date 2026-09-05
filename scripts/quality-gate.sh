#!/bin/sh
set -eu

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"
SPRING_DIR="$ROOT/services/spring-api"
SETUP_VALIDATION=false
CI_MODE=false
SCOPE=all
while [ "$#" -gt 0 ]; do
  case "$1" in
    --setup-validation) SETUP_VALIDATION=true ;;
    --ci) CI_MODE=true ;;
    --scope)
      shift
      [ "$#" -gt 0 ] || { echo "--scope 값이 필요합니다." >&2; exit 2; }
      SCOPE=$1
      ;;
    *) echo "알 수 없는 옵션: $1" >&2; exit 2 ;;
  esac
  shift
done

case "$SCOPE" in
  all|common|spring) ;;
  *) echo "지원하지 않는 품질 게이트 범위: $SCOPE" >&2; exit 2 ;;
esac

stage() {
  echo "[품질 게이트] $1"
}

run_spring_gradle() {
  (
    cd "$SPRING_DIR"
    ./gradlew --no-daemon "$@"
  )
}

run_bounded_spring_gradle() {
  STAGE_NAME=$1
  STAGE_TIMEOUT=$2
  EXPECTED_MARKER=$3
  shift 3
  (
    cd "$SPRING_DIR"
    python3 "$ROOT/scripts/gradle_stage_watchdog.py" --stage "$STAGE_NAME" --timeout-seconds "$STAGE_TIMEOUT" --post-suite-timeout-seconds 120 --expected-marker "$EXPECTED_MARKER" --diagnostics-dir "$SPRING_DIR/build/diagnostics" -- ./gradlew --no-daemon "$@"
  )
}

BRANCH=${GITHUB_HEAD_REF:-${GITHUB_REF_NAME:-$(git branch --show-current)}}
SHA=$(git rev-parse HEAD 2>/dev/null || printf 'UNBORN')

run_common_checks() {
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

  stage "배포 SQL 정책 검사"
  python3 scripts/deploy_sql_policy.py

  stage "REST 공통 계약 readiness 검사"
  python3 scripts/validate_rest_contracts.py
  python3 scripts/validate_profile_legal_contract.py
  python3 scripts/validate_fcm_departure_notification_contract.py
  python3 scripts/validate_push_notification_contract.py

  stage "관광지 검색·상세 계약 검사"
  python3 scripts/validate_places_contract.py

  stage "관심 장소 CRUD 계약 검사"
  python3 scripts/validate_saved_places_contract.py

  stage "여행 CRUD 계약 검사"
  python3 scripts/validate_trips_contract.py

  stage "여행 선호·교통 이벤트 계약 검사"
  python3 scripts/validate_preferences_transport_contract.py

  stage "복수 숙소 CRUD 계약 검사"
  python3 scripts/validate_accommodations_contract.py

  stage "불변 일정 조회·편집 계약 검사"
  python3 scripts/validate_schedules_contract.py

  stage "날씨 예보 API 계약 검사"
  python3 scripts/validate_weather_forecast_contract.py

  stage "가능성 계산·이동 구간 계약 검사"
  python3 scripts/validate_feasibility_legs_contract.py

  stage "위치정보 수집·보존·삭제 정책 계약 검사"
  python3 scripts/validate_location_retention_contract.py

  stage "저장소 자동화 테스트"
  python3 -m unittest discover -s .codex/hooks/tests -p 'test_*.py'
  python3 -m unittest discover -s scripts/git-hooks/tests -p 'test_*.py'
  python3 -m unittest discover -s scripts/tests -p 'test_*.py'
}

run_spring_checks() {
  stage "Spring 포맷 검사"
  run_spring_gradle spotlessCheck
  stage "Spring 컴파일"
  run_spring_gradle classes testClasses
  rm -rf "$SPRING_DIR/build/jacoco"
  if [ -e "$SPRING_DIR/build/jacoco" ]; then
    echo "stale JaCoCo execution data를 삭제하지 못했습니다." >&2
    exit 1
  fi
  stage "Spring 단위 테스트"
  run_spring_gradle unitTest
  stage "Spring Slice 테스트"
  run_spring_gradle sliceTest
  stage "Spring 통합 테스트"
  run_bounded_spring_gradle "integrationTest" 7200 "TIMING_JEJU_TEST_ROOT_COMPLETE task=:integrationTest" integrationTest
  stage "Spring OpenAPI 문서 생성"
  rm -f services/spring-api/build/openapi/openapi.json
  if [ -e services/spring-api/build/openapi/openapi.json ]; then
    echo "stale OpenAPI artifact를 삭제하지 못했습니다." >&2
    exit 1
  fi
  run_bounded_spring_gradle "openApiDocs" 900 "TIMING_JEJU_TEST_ROOT_COMPLETE task=:openApiDocsTest" openApiDocs
  if [ ! -s services/spring-api/build/openapi/openapi.json ]; then
    echo "OpenAPI artifact가 없거나 비어 있습니다." >&2
    exit 1
  fi
  stage "Spring OpenAPI 프론트엔드 readiness 검사"
  python3 scripts/validate_openapi_frontend_readiness.py services/spring-api/build/openapi/openapi.json --mode 31
  stage "Spring Architecture 테스트"
  run_spring_gradle architectureTest
  stage "Spring 전체 테스트와 커버리지"
  run_spring_gradle test jacocoTestReport jacocoTestCoverageVerification
  stage "Spring 애플리케이션 빌드"
  run_spring_gradle bootJar
  stage "Spring Docker 이미지·Compose·Health Check"
  ./scripts/docker-smoke-test.sh
}

case "$SCOPE" in
  all)
    run_common_checks
    run_spring_checks
    ;;
  common) run_common_checks ;;
  spring) run_spring_checks ;;
esac

if [ "$SCOPE" = all ] && [ "$SETUP_VALIDATION" = false ] && [ "$CI_MODE" = false ] && [ "$SHA" != "UNBORN" ]; then
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
    "openApiDocs": "SUCCESS",
    "dockerBuild": "SUCCESS",
    "dockerSmokeTest": "SUCCESS",
    "result": "SUCCESS",
}
Path(path).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
  echo "품질 게이트 상태 기록: $STATE_DIR/$SAFE_BRANCH.json"
fi

echo "[품질 게이트] 모든 단계 성공"
