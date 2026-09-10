#!/bin/sh
set -eu

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

SUPABASE_BIN=${SUPABASE_BIN:-supabase}
PSQL_BIN=${PSQL_BIN:-psql}
EXPECTED_CLI_VERSION=2.116.0
DATABASE_URL=${TIMING_JEJU_DATABASE_URL:-}

[ -n "$DATABASE_URL" ] || {
  echo "TIMING_JEJU_DATABASE_URL이 필요합니다." >&2
  exit 64
}
command -v "$SUPABASE_BIN" >/dev/null 2>&1 || exit 69
command -v "$PSQL_BIN" >/dev/null 2>&1 || exit 69
[ "$($SUPABASE_BIN --version)" = "$EXPECTED_CLI_VERSION" ] || {
  echo "Supabase CLI $EXPECTED_CLI_VERSION만 지원합니다." >&2
  exit 65
}

python3 scripts/location_cutover_group.py --supabase --check
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/timing-jeju-release.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT INT TERM
BOOTSTRAP_DIR="$TEMP_DIR/bootstrap"
FETCH_DIR="$TEMP_DIR/fetch"
mkdir -p "$BOOTSTRAP_DIR/supabase/migrations" "$FETCH_DIR/supabase/migrations"
cp supabase/config.toml "$BOOTSTRAP_DIR/supabase/config.toml"
cp supabase/config.toml "$FETCH_DIR/supabase/config.toml"

# Never expose immutable 017/018 or later files to the CLI sequential runner.
for migration in supabase/migrations/*.sql; do
  filename=${migration##*/}
  version=${filename%%_*}
  if [ "$version" -lt 20260918000017 ]; then
    cp "$migration" "$BOOTSTRAP_DIR/supabase/migrations/$filename"
  fi
done

"$SUPABASE_BIN" --workdir "$BOOTSTRAP_DIR" migration up \
  --db-url "$DATABASE_URL" --include-all
"$PSQL_BIN" "$DATABASE_URL" --no-psqlrc --set ON_ERROR_STOP=1 \
  --file db/local-postgres/location_cutover_supabase.sql
"$SUPABASE_BIN" --workdir "$FETCH_DIR" migration list --db-url "$DATABASE_URL"
"$SUPABASE_BIN" --workdir "$FETCH_DIR" --yes migration fetch --db-url "$DATABASE_URL"
python3 scripts/verify_supabase_fetched_migrations.py "$FETCH_DIR/supabase/migrations"
