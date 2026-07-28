#!/bin/sh
set -eu

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

SUPABASE_BIN=${SUPABASE_BIN:-supabase}
DOCKER_BIN=${DOCKER_BIN:-docker}
EXPECTED_CLI_VERSION=2.110.0
DB_CONTAINER=supabase_db_timing-jeju

cleanup() {
  "$SUPABASE_BIN" stop --no-backup >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

command -v "$SUPABASE_BIN" >/dev/null 2>&1 || {
  echo "Supabase CLI가 설치되지 않았습니다. 필요한 버전: $EXPECTED_CLI_VERSION" >&2
  exit 1
}

CLI_VERSION=$("$SUPABASE_BIN" --version 2>/dev/null) || {
  echo "Supabase CLI 버전을 확인할 수 없습니다." >&2
  exit 1
}
[ "$CLI_VERSION" = "$EXPECTED_CLI_VERSION" ] || {
  echo "Supabase CLI 버전이 다릅니다. 필요: $EXPECTED_CLI_VERSION, 현재: $CLI_VERSION" >&2
  exit 1
}

command -v "$DOCKER_BIN" >/dev/null 2>&1 || {
  echo "Docker가 설치되지 않았습니다." >&2
  exit 1
}
"$DOCKER_BIN" info >/dev/null 2>&1 || {
  echo "Docker daemon이 실행 중이 아닙니다." >&2
  exit 1
}

echo "[Supabase] 로컬 Auth·PostgreSQL 시작"
if ! "$SUPABASE_BIN" start >/dev/null 2>&1; then
  echo "Supabase 로컬 스택 시작에 실패했습니다. 'supabase start'를 확인하세요." >&2
  exit 1
fi

echo "[Supabase] 첫 번째 DB 초기화"
if ! "$SUPABASE_BIN" db reset >/dev/null 2>&1; then
  echo "첫 번째 'supabase db reset'에 실패했습니다." >&2
  exit 1
fi

echo "[Supabase] 반복 DB 초기화"
if ! "$SUPABASE_BIN" db reset >/dev/null 2>&1; then
  echo "두 번째 'supabase db reset'에 실패했습니다." >&2
  exit 1
fi

EXTENSION_COUNT=$(
  "$DOCKER_BIN" exec "$DB_CONTAINER" psql --no-psqlrc --tuples-only --no-align \
    --username postgres --dbname postgres \
    --command "select count(*) from pg_extension where extname in ('pgcrypto', 'postgis', 'btree_gist');"
)
[ "$EXTENSION_COUNT" = "3" ] || {
  echo "필수 확장 3개가 모두 활성화되지 않았습니다." >&2
  exit 1
}

TABLE_COUNT=$(
  "$DOCKER_BIN" exec "$DB_CONTAINER" psql --no-psqlrc --tuples-only --no-align \
    --username postgres --dbname postgres \
    --command "select count(*) from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE';"
)
[ "$TABLE_COUNT" -ge 46 ] || {
  echo "public 애플리케이션 테이블이 누락되었습니다. 현재: $TABLE_COUNT" >&2
  exit 1
}

SEED_PROFILE_COUNT=$(
  "$DOCKER_BIN" exec "$DB_CONTAINER" psql --no-psqlrc --tuples-only --no-align \
    --username postgres --dbname postgres \
    --command "select count(*) from public.user_profiles;"
)
[ "$SEED_PROFILE_COUNT" = "0" ] || {
  echo "빈 운영 시드에 예상하지 않은 사용자 프로필이 있습니다." >&2
  exit 1
}

echo "[Supabase] Auth·PostGIS·public 스키마·빈 시드 반복 초기화 성공"
