#!/bin/sh
set -eu

ROOT=$(git rev-parse --show-toplevel 2>/dev/null || pwd)
cd "$ROOT"

SUPABASE_BIN=${SUPABASE_BIN:-supabase}
DOCKER_BIN=${DOCKER_BIN:-docker}
EXPECTED_CLI_VERSION=2.110.0
DB_CONTAINER=supabase_db_timing-jeju
SPRING_DIR="$ROOT/services/spring-api"
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/timing-jeju-supabase-smoke.XXXXXX")

cleanup() {
  "$SUPABASE_BIN" stop --no-backup >/dev/null 2>&1 || true
  rm -rf "$TEMP_DIR"
}
trap cleanup EXIT INT TERM

command -v "$SUPABASE_BIN" >/dev/null 2>&1 || {
  echo "Supabase CLI가 설치되지 않았습니다. 필요한 버전: $EXPECTED_CLI_VERSION" >&2
  exit 1
}
command -v curl >/dev/null 2>&1 || {
  echo "curl이 설치되지 않았습니다." >&2
  exit 1
}
command -v python3 >/dev/null 2>&1 || {
  echo "Python 3가 설치되지 않았습니다." >&2
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

echo "[Supabase] 로컬 Auth 명령 계약과 실제 access token 검증"
STATUS_FILE="$TEMP_DIR/status.json"
SIGNUP_PAYLOAD_FILE="$TEMP_DIR/signup-payload.json"
SIGNUP_RESPONSE_FILE="$TEMP_DIR/signup-response.json"
TOKEN_FILE="$TEMP_DIR/access-token"
ISSUER_FILE="$TEMP_DIR/issuer"
ALGORITHM_FILE="$TEMP_DIR/algorithm"

umask 077
"$SUPABASE_BIN" status -o json >"$STATUS_FILE"
API_URL=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["API_URL"])' "$STATUS_FILE")
PUBLIC_KEY=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["PUBLISHABLE_KEY"])' "$STATUS_FILE")
JWT_SECRET=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["JWT_SECRET"])' "$STATUS_FILE")

REDIRECT_HEADERS_FILE="$TEMP_DIR/redirect-headers"
curl --silent --show-error --max-redirs 0 --get \
  "$API_URL/auth/v1/authorize" \
  --data-urlencode "provider=google" \
  --data-urlencode "redirect_to=https://evil.invalid/social-callback" \
  --dump-header "$REDIRECT_HEADERS_FILE" \
  --output /dev/null
REDIRECT_LOCATION=$(
  awk 'BEGIN { IGNORECASE=1 } /^location:/ { sub(/^[^:]*:[[:space:]]*/, ""); sub(/\r$/, ""); print; exit }' \
    "$REDIRECT_HEADERS_FILE"
)
case "$REDIRECT_LOCATION" in
  *evil.invalid*)
    echo "Supabase Auth가 미등록 redirect URL을 허용했습니다." >&2
    exit 1
    ;;
esac
echo "[Supabase] 미등록 redirect URL 차단 확인 성공"

TEST_EMAIL=$(python3 -c 'import uuid; print(f"security-smoke-{uuid.uuid4()}@example.test")')
TEST_PASSWORD=$(python3 -c 'import secrets; print("Tj!" + secrets.token_urlsafe(24))')
python3 - "$SIGNUP_PAYLOAD_FILE" "$TEST_EMAIL" "$TEST_PASSWORD" <<'PY'
import json
import sys

path, email, password = sys.argv[1:]
with open(path, "w", encoding="utf-8") as output:
    json.dump({"email": email, "password": password}, output)
PY

curl --fail --silent --show-error \
  --request POST "$API_URL/auth/v1/signup" \
  --header "apikey: $PUBLIC_KEY" \
  --header "Content-Type: application/json" \
  --data-binary "@$SIGNUP_PAYLOAD_FILE" \
  --output "$SIGNUP_RESPONSE_FILE"

python3 - "$SIGNUP_RESPONSE_FILE" "$TOKEN_FILE" "$ISSUER_FILE" "$ALGORITHM_FILE" "$API_URL" <<'PY'
import base64
import json
import sys
import uuid

response_path, token_path, issuer_path, algorithm_path, api_url = sys.argv[1:]
with open(response_path, encoding="utf-8") as response_file:
    response = json.load(response_file)
token = response.get("access_token")
if not isinstance(token, str) or not token:
    raise SystemExit("로컬 Auth 응답에 access token이 없습니다.")

segments = token.split(".")
if len(segments) != 3:
    raise SystemExit("로컬 Auth access token이 JWT 형식이 아닙니다.")

def decode(segment):
    padded = segment + "=" * (-len(segment) % 4)
    return json.loads(base64.urlsafe_b64decode(padded))

header = decode(segments[0])
claims = decode(segments[1])
audience = claims.get("aud")
audiences = audience if isinstance(audience, list) else [audience]
expected_issuer = f"{api_url}/auth/v1"
algorithm = header.get("alg")
if algorithm not in {"ES256", "RS256", "HS256"}:
    raise SystemExit("로컬 CLI access token 알고리즘을 지원하지 않습니다.")
if claims.get("iss") != expected_issuer:
    raise SystemExit("로컬 Auth issuer가 API URL 기반 계약과 다릅니다.")
if "authenticated" not in audiences or claims.get("role") != "authenticated":
    raise SystemExit("로컬 Auth audience 또는 role 계약이 다릅니다.")
uuid.UUID(claims.get("sub", ""))

with open(token_path, "w", encoding="utf-8") as token_file:
    token_file.write(token)
with open(issuer_path, "w", encoding="utf-8") as issuer_file:
    issuer_file.write(claims["iss"])
with open(algorithm_path, "w", encoding="utf-8") as algorithm_file:
    algorithm_file.write(algorithm)
print(f"[Supabase] 실제 token claim 확인: alg={algorithm}, aud=authenticated, role=authenticated, sub=UUID")
PY

ACCESS_TOKEN=$(python3 -c 'import sys; print(open(sys.argv[1], encoding="utf-8").read())' "$TOKEN_FILE")
JWT_ISSUER=$(python3 -c 'import sys; print(open(sys.argv[1], encoding="utf-8").read())' "$ISSUER_FILE")
JWT_ALGORITHM=$(python3 -c 'import sys; print(open(sys.argv[1], encoding="utf-8").read())' "$ALGORITHM_FILE")
if [ "$JWT_ALGORITHM" = "HS256" ]; then
  (
    cd "$SPRING_DIR"
    SPRING_PROFILES_ACTIVE=local-hs256 \
      SUPABASE_JWT_ISSUER="$JWT_ISSUER" \
      SUPABASE_JWT_AUDIENCE=authenticated \
      SUPABASE_JWT_SECRET="$JWT_SECRET" \
      SUPABASE_ACCESS_TOKEN="$ACCESS_TOKEN" \
      ./gradlew --no-daemon test --tests '*SupabaseLocalAuthIntegrationTest'
  )
else
  (
    cd "$SPRING_DIR"
    SPRING_PROFILES_ACTIVE=local \
      SUPABASE_JWT_ISSUER="$JWT_ISSUER" \
      SUPABASE_JWT_AUDIENCE=authenticated \
      SUPABASE_JWKS_URL="$API_URL/auth/v1/.well-known/jwks.json" \
      SUPABASE_ACCESS_TOKEN="$ACCESS_TOKEN" \
      ./gradlew --no-daemon test --tests '*SupabaseLocalAuthIntegrationTest'
  )
fi

echo "[Supabase] Auth·PostGIS·public 스키마·빈 시드·Spring 보호 API 통합 검증 성공"
