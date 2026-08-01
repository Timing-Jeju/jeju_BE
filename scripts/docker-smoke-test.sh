#!/bin/sh
set -eu

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"
PROJECT="timing-jeju-smoke"
UPGRADE_DB="timing_jeju_legacy_upgrade"
HOURS_CONFLICT_DB="timing_jeju_legacy_hours_conflict"
RESULT_DAY_CONFLICT_DB="timing_jeju_legacy_result_day_conflict"
RECOMMENDATION_DAY_CONFLICT_DB="timing_jeju_legacy_recommendation_day_conflict"
BASE_LINEAGE_CONFLICT_DB="timing_jeju_legacy_base_lineage_conflict"
REFERENCE_CONFLICT_DB="timing_jeju_legacy_reference_conflict"
TIMETABLE_CONFLICT_DB="timing_jeju_legacy_timetable_conflict"
OPEN_CLOSED_CONFLICT_DB="timing_jeju_legacy_open_closed_conflict"
SNAPSHOT_SCOPE_CONFLICT_DB="timing_jeju_legacy_snapshot_scope_conflict"
CHECKPOINT_STATUS_CONFLICT_DB="timing_jeju_legacy_checkpoint_conflict_status"
CHECKPOINT_SCOPE_CONFLICT_DB="timing_jeju_legacy_checkpoint_conflict_scope"
UNPARSED_LINEAGE_CONFLICT_DB="timing_jeju_legacy_lineage_unparsed"
RUN_LINEAGE_CONFLICT_DB="timing_jeju_legacy_lineage_run"
SOURCE_LINEAGE_CONFLICT_DB="timing_jeju_legacy_lineage_source"
OPTIONAL_LINEAGE_CONFLICT_DB="timing_jeju_legacy_lineage_optional"
CONCURRENCY_DB="timing_jeju_concurrency"
HOURS_CONFLICT_LOG=$(mktemp -t timing-jeju-hours-conflict.XXXXXX)
RESULT_DAY_CONFLICT_LOG=$(mktemp -t timing-jeju-result-day-conflict.XXXXXX)
CONSISTENCY_CONFLICT_LOG=$(mktemp -t timing-jeju-consistency-conflict.XXXXXX)

cleanup() {
  for database in \
    "$UPGRADE_DB" "$HOURS_CONFLICT_DB" "$RESULT_DAY_CONFLICT_DB" \
    "$RECOMMENDATION_DAY_CONFLICT_DB" \
    "$BASE_LINEAGE_CONFLICT_DB" \
    "$REFERENCE_CONFLICT_DB" "$TIMETABLE_CONFLICT_DB" \
    "$OPEN_CLOSED_CONFLICT_DB" "$SNAPSHOT_SCOPE_CONFLICT_DB" \
    "$CHECKPOINT_STATUS_CONFLICT_DB" "$CHECKPOINT_SCOPE_CONFLICT_DB" \
    "$UNPARSED_LINEAGE_CONFLICT_DB" "$RUN_LINEAGE_CONFLICT_DB" \
    "$SOURCE_LINEAGE_CONFLICT_DB" "$OPTIONAL_LINEAGE_CONFLICT_DB" \
    "$CONCURRENCY_DB"
  do
    docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
      dropdb --username timing_jeju_test --if-exists --force "$database" \
      >/dev/null 2>&1 || true
  done
  if [ -f "$HOURS_CONFLICT_LOG" ]; then
    rm -f "$HOURS_CONFLICT_LOG"
  fi
  if [ -f "$RESULT_DAY_CONFLICT_LOG" ]; then
    rm -f "$RESULT_DAY_CONFLICT_LOG"
  fi
  if [ -f "$CONSISTENCY_CONFLICT_LOG" ]; then
    rm -f "$CONSISTENCY_CONFLICT_LOG"
  fi
  docker compose -p "$PROJECT" -f compose.test.yml down -v --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

command -v docker >/dev/null || { echo "Docker가 설치되지 않았습니다." >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker daemon이 실행 중이 아닙니다." >&2; exit 1; }

echo "[Docker] 이미지 빌드와 격리 Compose 실행"
docker compose -p "$PROJECT" -f compose.test.yml up -d --build

attempt=1
while [ "$attempt" -le 60 ]; do
  if curl --fail --silent http://127.0.0.1:18080/actuator/health | grep -q '"status":"UP"'; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 2
done

if [ "$attempt" -gt 60 ]; then
  echo "[Docker] Health Check 실패" >&2
  docker compose -p "$PROJECT" -f compose.test.yml ps >&2 || true
  docker compose -p "$PROJECT" -f compose.test.yml logs --no-color api postgres >&2 || true
  exit 1
fi

echo "[Docker] Health Check 성공"

assert_consistency_upgrade_failure() {
  database=$1
  fixture=$2
  expected_pattern=$3
  expected_identifier=$4
  label=$5

  docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
    createdb --username timing_jeju_test "$database"

  for setup_sql in \
    /docker-entrypoint-initdb.d/001_auth_compat.sql \
    /docker-entrypoint-initdb.d/002_application_schema.sql \
    /docker-entrypoint-initdb.d/003_database_integrity_hardening.sql \
    /docker-entrypoint-initdb.d/004_external_ingestion_foundation.sql \
    "$fixture"
  do
    docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
      psql --no-psqlrc --set ON_ERROR_STOP=1 \
      --username timing_jeju_test --dbname "$database" \
      --file "$setup_sql"
  done

  : >"$CONSISTENCY_CONFLICT_LOG"
  if docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
    psql --no-psqlrc --set ON_ERROR_STOP=1 --set VERBOSITY=verbose \
    --single-transaction \
    --username timing_jeju_test --dbname "$database" \
    --file /docker-entrypoint-initdb.d/005_ingestion_consistency_hardening.sql \
    >"$CONSISTENCY_CONFLICT_LOG" 2>&1; then
    echo "[Docker] $label audit가 실패하지 않았습니다." >&2
    exit 1
  fi

  if ! grep -q "$expected_pattern" "$CONSISTENCY_CONFLICT_LOG" \
     || ! grep -q "$expected_identifier" "$CONSISTENCY_CONFLICT_LOG"; then
    echo "[Docker] $label audit가 예상한 오류와 행 식별자를 반환하지 않았습니다." >&2
    sed -n '1,100p' "$CONSISTENCY_CONFLICT_LOG" >&2
    exit 1
  fi

  docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
    dropdb --username timing_jeju_test "$database"
  echo "[Docker] $label audit 검사 성공"
}

assert_schedule_upgrade_failure() {
  database=$1
  fixture=$2
  expected_pattern=$3
  expected_identifier=$4
  label=$5

  docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
    createdb --username timing_jeju_test "$database"

  for setup_sql in \
    /docker-entrypoint-initdb.d/001_auth_compat.sql \
    /docker-entrypoint-initdb.d/002_application_schema.sql \
    "$fixture" \
    /docker-entrypoint-initdb.d/003_database_integrity_hardening.sql \
    /docker-entrypoint-initdb.d/004_external_ingestion_foundation.sql \
    /docker-entrypoint-initdb.d/005_ingestion_consistency_hardening.sql
  do
    docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
      psql --no-psqlrc --set ON_ERROR_STOP=1 \
      --username timing_jeju_test --dbname "$database" \
      --file "$setup_sql"
  done

  : >"$RESULT_DAY_CONFLICT_LOG"
  if docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
    psql --no-psqlrc --set ON_ERROR_STOP=1 --set VERBOSITY=verbose \
    --single-transaction \
    --username timing_jeju_test --dbname "$database" \
    --file /docker-entrypoint-initdb.d/006_schedule_consistency_hardening.sql \
    >"$RESULT_DAY_CONFLICT_LOG" 2>&1; then
    echo "[Docker] $label audit가 실패하지 않았습니다." >&2
    exit 1
  fi

  if ! grep -q "$expected_pattern" "$RESULT_DAY_CONFLICT_LOG" \
     || ! grep -q "$expected_identifier" "$RESULT_DAY_CONFLICT_LOG"; then
    echo "[Docker] $label audit가 예상한 오류와 행 식별자를 반환하지 않았습니다." >&2
    sed -n '1,100p' "$RESULT_DAY_CONFLICT_LOG" >&2
    exit 1
  fi

  docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
    dropdb --username timing_jeju_test "$database"
  echo "[Docker] $label audit 검사 성공"
}

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  createdb --username timing_jeju_test "$UPGRADE_DB"

for upgrade_sql in \
  /docker-entrypoint-initdb.d/001_auth_compat.sql \
  /docker-entrypoint-initdb.d/002_application_schema.sql \
  /queries/legacy_v1_upgrade_fixture.sql \
  /docker-entrypoint-initdb.d/003_database_integrity_hardening.sql \
  /docker-entrypoint-initdb.d/004_external_ingestion_foundation.sql \
  /queries/legacy_foundation_running_scope_fixture.sql \
  /docker-entrypoint-initdb.d/005_ingestion_consistency_hardening.sql \
  /docker-entrypoint-initdb.d/006_schedule_consistency_hardening.sql \
  /docker-entrypoint-initdb.d/007_import_run_lineage_retention.sql \
  /queries/legacy_v1_upgrade_contract.sql
do
  docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
    psql --no-psqlrc --set ON_ERROR_STOP=1 \
    --username timing_jeju_test --dbname "$UPGRADE_DB" \
    --file "$upgrade_sql"
done

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  dropdb --username timing_jeju_test "$UPGRADE_DB"
echo "[Docker] v1→최신 migration 업그레이드 계약 검사 성공"

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  createdb --username timing_jeju_test "$HOURS_CONFLICT_DB"

for conflict_setup_sql in \
  /docker-entrypoint-initdb.d/001_auth_compat.sql \
  /docker-entrypoint-initdb.d/002_application_schema.sql \
  /queries/legacy_v1_cross_day_conflict_fixture.sql \
  /docker-entrypoint-initdb.d/003_database_integrity_hardening.sql \
  /docker-entrypoint-initdb.d/004_external_ingestion_foundation.sql
do
  docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
    psql --no-psqlrc --set ON_ERROR_STOP=1 \
    --username timing_jeju_test --dbname "$HOURS_CONFLICT_DB" \
    --file "$conflict_setup_sql"
done

if docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  psql --no-psqlrc --set ON_ERROR_STOP=1 --set VERBOSITY=verbose \
  --single-transaction \
  --username timing_jeju_test --dbname "$HOURS_CONFLICT_DB" \
  --file /docker-entrypoint-initdb.d/005_ingestion_consistency_hardening.sql \
  >"$HOURS_CONFLICT_LOG" 2>&1; then
  echo "[Docker] v1 교차 요일 충돌 audit가 실패하지 않았습니다." >&2
  exit 1
fi

if ! grep -q "23P01.*legacy operating hours failed cross-day overlap audit" \
  "$HOURS_CONFLICT_LOG" \
   || ! grep -q "ea100000-0000-0000-0000-000000000001" \
  "$HOURS_CONFLICT_LOG"; then
  echo "[Docker] v1 교차 요일 충돌 audit가 예상한 오류를 반환하지 않았습니다." >&2
  sed -n '1,80p' "$HOURS_CONFLICT_LOG" >&2
  exit 1
fi

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  dropdb --username timing_jeju_test "$HOURS_CONFLICT_DB"
echo "[Docker] v1 교차 요일 영업시간 충돌 audit 검사 성공"

assert_schedule_upgrade_failure \
  "$RESULT_DAY_CONFLICT_DB" \
  /queries/legacy_v1_result_day_conflict_fixture.sql \
  "23514.*legacy day-scoped result failed same-day lineage audit" \
  "ed000000-0000-0000-0000-000000000070" \
  "v1 날씨 결과 Day lineage 충돌"

assert_schedule_upgrade_failure \
  "$RECOMMENDATION_DAY_CONFLICT_DB" \
  /queries/legacy_v1_recommendation_day_conflict_fixture.sql \
  "23514.*legacy day-scoped result failed same-day lineage audit" \
  "ed000000-0000-0000-0000-000000000080" \
  "v1 추천 결과 Day lineage 충돌"

assert_schedule_upgrade_failure \
  "$BASE_LINEAGE_CONFLICT_DB" \
  /queries/legacy_v1_base_lineage_conflict_fixture.sql \
  "23514.*legacy schedule base lineage is invalid" \
  "ee000000-0000-0000-0000-000000000021" \
  "v1 일정 base 계보 충돌"

assert_consistency_upgrade_failure \
  "$REFERENCE_CONFLICT_DB" \
  /queries/legacy_foundation_external_reference_conflict_fixture.sql \
  "23P01.*legacy external reference validity overlap audit failed" \
  "e7100000" \
  "legacy 외부 코드 유효기간 충돌"

assert_consistency_upgrade_failure \
  "$TIMETABLE_CONFLICT_DB" \
  /queries/legacy_foundation_timetable_conflict_fixture.sql \
  "23P01.*legacy timetable validity overlap audit failed" \
  "e7400000" \
  "legacy 시간표 유효기간 충돌"

assert_consistency_upgrade_failure \
  "$OPEN_CLOSED_CONFLICT_DB" \
  /queries/legacy_foundation_open_closed_conflict_fixture.sql \
  "23P01.*legacy operating hours open-closed overlap audit failed" \
  "e7600000" \
  "legacy 영업·휴무 상태 충돌"

assert_consistency_upgrade_failure \
  "$SNAPSHOT_SCOPE_CONFLICT_DB" \
  /queries/legacy_foundation_multi_snapshot_scope_fixture.sql \
  "23514.*existing import run spans multiple snapshot source scopes" \
  "e7700000" \
  "legacy 단일 실행 다중 snapshot scope 충돌"

assert_consistency_upgrade_failure \
  "$CHECKPOINT_STATUS_CONFLICT_DB" \
  /queries/legacy_foundation_checkpoint_status_conflict_fixture.sql \
  "23514.*legacy checkpoint succeeded-run audit failed" \
  "e7910000" \
  "legacy checkpoint 비성공 run 충돌"

assert_consistency_upgrade_failure \
  "$CHECKPOINT_SCOPE_CONFLICT_DB" \
  /queries/legacy_foundation_checkpoint_scope_conflict_fixture.sql \
  "23514.*legacy checkpoint succeeded-run audit failed" \
  "e7930000" \
  "legacy checkpoint source scope 충돌"

assert_consistency_upgrade_failure \
  "$UNPARSED_LINEAGE_CONFLICT_DB" \
  /queries/legacy_foundation_unparsed_lineage_conflict_fixture.sql \
  "23514.*legacy normalized source lineage audit failed" \
  "e8020000" \
  "legacy 미파싱 snapshot 정규화 계보 충돌"

assert_consistency_upgrade_failure \
  "$RUN_LINEAGE_CONFLICT_DB" \
  /queries/legacy_foundation_run_lineage_conflict_fixture.sql \
  "23514.*legacy normalized source lineage audit failed" \
  "e8120000" \
  "legacy snapshot·정규화 run 계보 충돌"

assert_consistency_upgrade_failure \
  "$SOURCE_LINEAGE_CONFLICT_DB" \
  /queries/legacy_foundation_source_lineage_conflict_fixture.sql \
  "23514.*legacy normalized source lineage audit failed" \
  "e8220000" \
  "legacy snapshot·정규화 source scope 충돌"

assert_consistency_upgrade_failure \
  "$OPTIONAL_LINEAGE_CONFLICT_DB" \
  /queries/legacy_foundation_optional_lineage_conflict_fixture.sql \
  "23514.*legacy normalized source lineage audit failed" \
  "e8330000" \
  "legacy snapshot-backed optional marker 계보 충돌"

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  createdb --username timing_jeju_test "$CONCURRENCY_DB"

for concurrency_sql in \
  /docker-entrypoint-initdb.d/001_auth_compat.sql \
  /docker-entrypoint-initdb.d/002_application_schema.sql \
  /docker-entrypoint-initdb.d/003_database_integrity_hardening.sql \
  /docker-entrypoint-initdb.d/004_external_ingestion_foundation.sql \
  /docker-entrypoint-initdb.d/005_ingestion_consistency_hardening.sql \
  /docker-entrypoint-initdb.d/006_schedule_consistency_hardening.sql \
  /docker-entrypoint-initdb.d/007_import_run_lineage_retention.sql \
  /queries/database_concurrency_contract.sql
do
  docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
    psql --no-psqlrc --set ON_ERROR_STOP=1 \
    --username timing_jeju_test --dbname "$CONCURRENCY_DB" \
    --file "$concurrency_sql"
done

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  dropdb --username timing_jeju_test "$CONCURRENCY_DB"
echo "[Docker] 실제 2세션 동시성 계약 검사 성공"

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  psql --no-psqlrc --set ON_ERROR_STOP=1 \
  --username timing_jeju_test --dbname timing_jeju_test \
  --file /queries/schema_contract.sql
echo "[Docker] 스키마 계약 검사 성공"

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  psql --no-psqlrc --set ON_ERROR_STOP=1 \
  --username timing_jeju_test --dbname timing_jeju_test \
  --file /queries/database_negative_constraints.sql
echo "[Docker] 음수 무결성 계약 검사 성공"

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  psql --no-psqlrc --set ON_ERROR_STOP=1 \
  --username timing_jeju_test --dbname timing_jeju_test \
  --file /queries/smoke_check.sql
echo "[Docker] PostGIS·fixture 계약 검사 성공"
