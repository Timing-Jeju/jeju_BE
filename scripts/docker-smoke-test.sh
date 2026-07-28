#!/bin/sh
set -eu

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"
PROJECT="timing-jeju-smoke"

cleanup() {
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
docker compose -p "$PROJECT" -f compose.test.yml exec -T postgres \
  psql --no-psqlrc --username timing_jeju_test --dbname timing_jeju_test \
  --file /queries/smoke_check.sql
echo "[Docker] PostGIS·스키마·fixture 계약 검사 성공"
