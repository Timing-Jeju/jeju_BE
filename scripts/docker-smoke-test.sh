#!/bin/sh
set -eu

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"
RUN_ID="$(date -u +%Y%m%d%H%M%S)-$$"
PROJECT="timing-jeju-smoke-${RUN_ID}"
UPGRADE_DB="timing_jeju_legacy_upgrade"
ORIGIN_DEVELOP_DB="canonical_origin_develop_upgrade"
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
  cleanup_status=0
  for database in \
    "$UPGRADE_DB" "$ORIGIN_DEVELOP_DB" "$HOURS_CONFLICT_DB" "$RESULT_DAY_CONFLICT_DB" \
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
  if ! docker compose -p "$PROJECT" -f compose.test.yml down -v --remove-orphans \
    >/dev/null 2>&1; then
    echo "[Docker] smoke project 정리에 실패했습니다: $PROJECT" >&2
    cleanup_status=1
  fi

  if image_residue=$(docker image ls \
    --filter "reference=${PROJECT}-api:latest" --quiet 2>/dev/null); then
    if [ -n "$image_residue" ] \
      && ! docker image rm "${PROJECT}-api:latest" >/dev/null 2>&1; then
      echo "[Docker] smoke API 이미지 정리에 실패했습니다: ${PROJECT}-api:latest" >&2
      cleanup_status=1
    fi
  else
    echo "[Docker] smoke API 이미지 상태를 확인하지 못했습니다: $PROJECT" >&2
    cleanup_status=1
  fi

  if compose_residue=$(docker compose -p "$PROJECT" -f compose.test.yml ps -aq 2>/dev/null); then
    if [ -n "$compose_residue" ]; then
      echo "[Docker] smoke container residue가 남았습니다: $compose_residue" >&2
      cleanup_status=1
    fi
  else
    echo "[Docker] smoke container residue를 확인하지 못했습니다: $PROJECT" >&2
    cleanup_status=1
  fi
  if network_residue=$(docker network ls \
    --filter "label=com.docker.compose.project=$PROJECT" --quiet 2>/dev/null); then
    if [ -n "$network_residue" ]; then
      echo "[Docker] smoke network residue가 남았습니다: $network_residue" >&2
      cleanup_status=1
    fi
  else
    echo "[Docker] smoke network residue를 확인하지 못했습니다: $PROJECT" >&2
    cleanup_status=1
  fi
  if volume_residue=$(docker volume ls \
    --filter "label=com.docker.compose.project=$PROJECT" --quiet 2>/dev/null); then
    if [ -n "$volume_residue" ]; then
      echo "[Docker] smoke volume residue가 남았습니다: $volume_residue" >&2
      cleanup_status=1
    fi
  else
    echo "[Docker] smoke volume residue를 확인하지 못했습니다: $PROJECT" >&2
    cleanup_status=1
  fi
  if image_residue=$(docker image ls \
    --filter "reference=${PROJECT}-api:latest" --quiet 2>/dev/null); then
    if [ -n "$image_residue" ]; then
      echo "[Docker] smoke API image residue가 남았습니다: ${PROJECT}-api:latest" >&2
      cleanup_status=1
    fi
  else
    echo "[Docker] smoke API image residue를 확인하지 못했습니다: $PROJECT" >&2
    cleanup_status=1
  fi

  return "$cleanup_status"
}

finish() {
  original_status=$?
  trap - EXIT INT TERM
  cleanup_status=0
  cleanup || cleanup_status=$?

  if [ "$original_status" -ne 0 ]; then
    exit "$original_status"
  fi
  if [ "$cleanup_status" -ne 0 ]; then
    exit 70
  fi
  exit 0
}

resolve_api_port() {
  published=$1
  entry_count=$(printf '%s\n' "$published" | awk 'NF { count++ } END { print count + 0 }')
  if [ "$entry_count" -ne 1 ]; then
    echo "[Docker] API publish port가 하나가 아닙니다." >&2
    return 1
  fi

  case "$published" in
    *:*) port=${published##*:} ;;
    *)
      echo "[Docker] API publish port 형식이 올바르지 않습니다." >&2
      return 1
      ;;
  esac

  case "$port" in
    ''|*[!0-9]*)
      echo "[Docker] API publish port가 정수가 아닙니다." >&2
      return 1
      ;;
  esac
  if [ "${#port}" -gt 5 ]; then
    echo "[Docker] API publish port 범위가 올바르지 않습니다." >&2
    return 1
  fi
  if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ]; then
    echo "[Docker] API publish port 범위가 올바르지 않습니다." >&2
    return 1
  fi

  printf '%s\n' "$port"
}

trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

command -v docker >/dev/null || { echo "Docker가 설치되지 않았습니다." >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "Docker daemon이 실행 중이 아닙니다." >&2; exit 1; }
echo "[Docker] 이미지 빌드와 격리 Compose 실행"
docker compose -p "$PROJECT" -f compose.test.yml up -d --build

PUBLISHED_API_PORT=$(docker compose -p "$PROJECT" -f compose.test.yml port api 8080) || {
  echo "[Docker] 현재 smoke API의 publish port를 조회하지 못했습니다." >&2
  exit 1
}
API_PORT=$(resolve_api_port "$PUBLISHED_API_PORT")

attempt=1
while [ "$attempt" -le 60 ]; do
  if curl --fail --silent "http://127.0.0.1:${API_PORT}/actuator/health" | grep -q '"status":"UP"'; then
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

FRESH_CANONICAL_FINGERPRINT=$(docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --username timing_jeju_test --dbname timing_jeju_test \
  --file /queries/canonical_migration_fingerprint.sql)

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  createdb --username timing_jeju_test "$ORIGIN_DEVELOP_DB"

# canonical_origin_develop_upgrade replays the immutable prefix and then the reserved suffix.
# canonical_schedule_50_51_upgrade is observable as distinct 045 and 046 history steps.
for canonical_sql in $(sed -n \
  's#^[[:space:]]*- ./[^:]*:\(/docker-entrypoint-initdb.d/[0-9][0-9][0-9]_[^:]*\.sql\):ro$#\1#p' \
  compose.test.yml)
do
  if [ "$canonical_sql" = "/docker-entrypoint-initdb.d/099_seed_fixtures.sql" ]; then
    continue
  fi
  docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
    psql --no-psqlrc --set ON_ERROR_STOP=1 \
    --username timing_jeju_test --dbname "$ORIGIN_DEVELOP_DB" \
    --file "$canonical_sql"
done

UPGRADE_CANONICAL_FINGERPRINT=$(docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --username timing_jeju_test --dbname "$ORIGIN_DEVELOP_DB" \
  --file /queries/canonical_migration_fingerprint.sql)

if [ "$FRESH_CANONICAL_FINGERPRINT" != "$UPGRADE_CANONICAL_FINGERPRINT" ]; then
  echo "[Docker] fresh와 origin/develop upgrade schema/ACL fingerprint가 다릅니다." >&2
  exit 1
fi
docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  dropdb --username timing_jeju_test "$ORIGIN_DEVELOP_DB"
echo "[Docker] canonical fresh/origin-develop/#50-then-#51 fingerprint 검사 성공"

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
  /docker-entrypoint-initdb.d/008_api_idempotency_registry.sql \
  /docker-entrypoint-initdb.d/009_async_run_worker_runtime.sql \
  /docker-entrypoint-initdb.d/010_import_run_lifecycle_fencing.sql \
  /queries/legacy_snapshot_storage_upgrade_fixture.sql \
  /docker-entrypoint-initdb.d/011_external_snapshot_storage.sql \
  /docker-entrypoint-initdb.d/012_tour_api_operation_provenance.sql \
  /docker-entrypoint-initdb.d/013_tour_api_detail_info_operation.sql \
  /docker-entrypoint-initdb.d/014_tour_api_place_images_operation.sql \
  /docker-entrypoint-initdb.d/015_tour_api_incremental_sync.sql \
  /docker-entrypoint-initdb.d/018_kma_village_forecast_version.sql \
  /docker-entrypoint-initdb.d/021_recommended_stay_policy.sql \
  /docker-entrypoint-initdb.d/024_tago_arrival_cache.sql \
  /docker-entrypoint-initdb.d/025_tago_arrival_flight_state.sql \
  /docker-entrypoint-initdb.d/026_completed_provider_data_health_index.sql \
  /docker-entrypoint-initdb.d/027_completed_provider_snapshot_retention_index.sql \
  /docker-entrypoint-initdb.d/028_schedule_revision_run_foundation.sql \
  /docker-entrypoint-initdb.d/029_compute_run_input_snapshot.sql \
  /docker-entrypoint-initdb.d/030_legal_documents_consents.sql \
  /docker-entrypoint-initdb.d/031_trip_create_contract.sql \
  /docker-entrypoint-initdb.d/032_saved_places_api.sql \
  /docker-entrypoint-initdb.d/033_push_device_notification_preferences.sql \
  /docker-entrypoint-initdb.d/034_push_notification_server_writer_boundary.sql \
  /docker-entrypoint-initdb.d/035_mcp_private_http_client.sql \
  /docker-entrypoint-initdb.d/036_trip_update_delete_contract.sql \
  /docker-entrypoint-initdb.d/037_schedule_item_create_contract.sql \
  /docker-entrypoint-initdb.d/038_trip_preferences_replace_contract.sql \
  /docker-entrypoint-initdb.d/039_trip_preferences_owner_read_helper.sql \
  /docker-entrypoint-initdb.d/040_trip_accommodation_contract.sql \
  /docker-entrypoint-initdb.d/041_trip_transport_event_contract.sql \
  /docker-entrypoint-initdb.d/042_trip_place_preference_contract.sql \
  /docker-entrypoint-initdb.d/043_trip_calendar_child_invariant_correction.sql \
  /docker-entrypoint-initdb.d/044_profile_image_storage.sql \
  /docker-entrypoint-initdb.d/045_schedule_item_required_references.sql \
  /docker-entrypoint-initdb.d/046_schedule_item_required_references_correction.sql \
  /docker-entrypoint-initdb.d/047_jeju_timetable_route_scope.sql \
  /docker-entrypoint-initdb.d/048_compute_run_input_location_cleanup.sql \
  /docker-entrypoint-initdb.d/049_private_trip_ownership_helper.sql \
  /docker-entrypoint-initdb.d/050_schedule_title_only_sealing_correction.sql \
  /docker-entrypoint-initdb.d/051_schedule_item_closed_facts.sql \
  /docker-entrypoint-initdb.d/052_planned_anchor_resolver.sql \
  /queries/legacy_v1_upgrade_contract.sql
do
  docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
    psql --no-psqlrc --set ON_ERROR_STOP=1 \
    --username timing_jeju_test --dbname "$UPGRADE_DB" \
    --file "$upgrade_sql"
done

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  psql --no-psqlrc --set ON_ERROR_STOP=1 \
  --username timing_jeju_test --dbname "$UPGRADE_DB" \
  --file /queries/legacy_schedule_item_reference_conflict_fixture.sql

: >"$RESULT_DAY_CONFLICT_LOG"
if docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  psql --no-psqlrc --set ON_ERROR_STOP=1 --set VERBOSITY=verbose \
  --single-transaction --username timing_jeju_test --dbname "$UPGRADE_DB" \
  --file /docker-entrypoint-initdb.d/046_schedule_item_required_references_correction.sql \
  >"$RESULT_DAY_CONFLICT_LOG" 2>&1; then
  echo "[Docker] v1 item 필수 참조 audit가 실패하지 않았습니다." >&2
  exit 1
fi
if ! grep -q "23514.*legacy schedule item required reference audit failed" \
  "$RESULT_DAY_CONFLICT_LOG" \
   || ! grep -q "e4300000-0000-0000-0000-000000000001" \
  "$RESULT_DAY_CONFLICT_LOG"; then
  echo "[Docker] v1 item 필수 참조 audit가 예상한 오류를 반환하지 않았습니다." >&2
  exit 1
fi

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
  /docker-entrypoint-initdb.d/008_api_idempotency_registry.sql \
  /docker-entrypoint-initdb.d/009_async_run_worker_runtime.sql \
  /docker-entrypoint-initdb.d/010_import_run_lifecycle_fencing.sql \
  /docker-entrypoint-initdb.d/011_external_snapshot_storage.sql \
  /docker-entrypoint-initdb.d/012_tour_api_operation_provenance.sql \
  /docker-entrypoint-initdb.d/013_tour_api_detail_info_operation.sql \
  /docker-entrypoint-initdb.d/014_tour_api_place_images_operation.sql \
  /docker-entrypoint-initdb.d/015_tour_api_incremental_sync.sql \
  /docker-entrypoint-initdb.d/018_kma_village_forecast_version.sql \
  /docker-entrypoint-initdb.d/021_recommended_stay_policy.sql \
  /docker-entrypoint-initdb.d/024_tago_arrival_cache.sql \
  /docker-entrypoint-initdb.d/025_tago_arrival_flight_state.sql \
  /docker-entrypoint-initdb.d/026_completed_provider_data_health_index.sql \
  /docker-entrypoint-initdb.d/027_completed_provider_snapshot_retention_index.sql \
  /docker-entrypoint-initdb.d/028_schedule_revision_run_foundation.sql \
  /docker-entrypoint-initdb.d/029_compute_run_input_snapshot.sql \
  /docker-entrypoint-initdb.d/030_legal_documents_consents.sql \
  /docker-entrypoint-initdb.d/031_trip_create_contract.sql \
  /docker-entrypoint-initdb.d/032_saved_places_api.sql \
  /docker-entrypoint-initdb.d/033_push_device_notification_preferences.sql \
  /docker-entrypoint-initdb.d/034_push_notification_server_writer_boundary.sql \
  /docker-entrypoint-initdb.d/035_mcp_private_http_client.sql \
  /docker-entrypoint-initdb.d/036_trip_update_delete_contract.sql \
  /docker-entrypoint-initdb.d/037_schedule_item_create_contract.sql \
  /docker-entrypoint-initdb.d/038_trip_preferences_replace_contract.sql \
  /docker-entrypoint-initdb.d/039_trip_preferences_owner_read_helper.sql \
  /docker-entrypoint-initdb.d/040_trip_accommodation_contract.sql \
  /docker-entrypoint-initdb.d/041_trip_transport_event_contract.sql \
  /docker-entrypoint-initdb.d/042_trip_place_preference_contract.sql \
  /docker-entrypoint-initdb.d/043_trip_calendar_child_invariant_correction.sql \
  /docker-entrypoint-initdb.d/044_profile_image_storage.sql \
  /docker-entrypoint-initdb.d/045_schedule_item_required_references.sql \
  /docker-entrypoint-initdb.d/046_schedule_item_required_references_correction.sql \
  /docker-entrypoint-initdb.d/047_jeju_timetable_route_scope.sql \
  /docker-entrypoint-initdb.d/048_compute_run_input_location_cleanup.sql \
  /docker-entrypoint-initdb.d/049_private_trip_ownership_helper.sql \
  /docker-entrypoint-initdb.d/050_schedule_title_only_sealing_correction.sql \
  /docker-entrypoint-initdb.d/051_schedule_item_closed_facts.sql \
  /docker-entrypoint-initdb.d/052_planned_anchor_resolver.sql \
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

PREFERENCE_MIGRATION_CATALOG=$(docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 \
  --username timing_jeju_test --dbname timing_jeju_test \
  --command "
    select concat_ws(':',
      (select count(*) from pg_catalog.pg_proc
       where oid in (
         'public.trip_preference_categories_valid(text[])'::regprocedure,
         'public.trip_preference_regions_valid(text[])'::regprocedure,
         'public.validate_trip_transport_mode_set()'::regprocedure
       )),
      (select count(*) from pg_catalog.pg_trigger
       where not tgisinternal
         and tgname in (
           'trg_trip_preferences_transport_mode_aggregate',
           'trg_trip_transport_modes_aggregate'
         )),
      (select count(*) from pg_catalog.pg_constraint
       where conrelid = 'public.trip_preferences'::regclass
         and conname in (
           'ck_trip_preferences_categories_valid',
           'ck_trip_preferences_arrival_region_valid',
           'ck_trip_preferences_departure_region_valid',
           'ck_trip_preferences_regions_valid'
         )),
      (select count(*) from pg_catalog.pg_proc
       where oid = 'public.validate_trip_transport_mode_set()'::regprocedure
         and prosrc like '%trip_transport_modes_aggregate_check%'))")

if [ "$PREFERENCE_MIGRATION_CATALOG" != "3:2:4:1" ]; then
  echo "[Docker] #46 migration residue/catalog 검사 실패: $PREFERENCE_MIGRATION_CATALOG" >&2
  exit 1
fi
echo "[Docker] #46 migration residue/catalog 검사 성공"


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

docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  psql --no-psqlrc --set ON_ERROR_STOP=1 \
  --username timing_jeju_test --dbname timing_jeju_test \
  --file /queries/private_trip_ownership_helper_contract.sql
echo "[Docker] PostGIS·fixture 계약 검사 성공"
