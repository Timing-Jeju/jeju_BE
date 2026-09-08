# 타이밍제주 Spring–MCP private HTTP 연동 명세 v2.0

## 1. 기준 계약

planner runtime 계약은 [`Timing-Jeju/jeju_AI`](https://github.com/Timing-Jeju/jeju_AI)의 Pydantic `0.7.0`이 유일한 원본이다. transport·인증·경계 설명은 AI의 [`docs/FASTAPI_MCP_CONTRACT.md`](https://github.com/Timing-Jeju/jeju_AI/blob/develop/docs/FASTAPI_MCP_CONTRACT.md)를 따른다. BE는 release artifact인 `mcp-tools-v0.7.json`만 포함하며 Pydantic schema를 Java DTO로 다시 정의하지 않는다.

route 계산·route fact·TTL cache·fallback은 AI가 소유한다. Spring은 private MCP 연결·계약 검증·감사와 제품 DB 결과 저장을 소유한다. Spring은 AI의 TMAP route 정책이나 cache를 Java application package로 복제하지 않는다.

| 항목 | 값 |
| --- | --- |
| 공개 API | Spring Boot `/api/v1/**` |
| 내부 계산 | FastMCP stateless Streamable HTTP `POST /mcp` |
| client | Spring AI MCP sync client 2.0.1 |
| 인증 | private network + 매 요청 RS256 service JWT |
| 결과 본문 | `result.structuredContent` |
| contract | `0.7.0` |
| 사용자 JWT 전달 | 금지 |

## 2. 현재 여섯 도구

| MCP 도구 | BE 사용처 |
| --- | --- |
| `recommend_jeju_day_trips` | Day 생성, 빈시간 후보 탐색 |
| `evaluate_jeju_day_trip` | 추천 3개 평가, revision/feasibility 평가 |
| `revalidate_jeju_day_trip` | same-day live 재검증과 recovery option |
| `search_jeju_places` | 아직 crosswalk되지 않은 structured selector 확인 |
| `inspect_jeju_bus_stop` | 선택된 정류장·노선 mapping 확인 |
| `preview_jeju_transfer` | revision에서 변경된 인접 leg와 대안 계산 |

BE가 AI rank를 다시 계산하지 않는다. 추천 성공은 rank `1,2,3`과 `balanced`, `relaxed`, `experience_max`가 각각 정확히 하나여야 한다. 서로 다른 유효 추천 세 개를 만들 수 없으면 candidate를 저장하지 않고 `insufficient_feasible_routes`를 유지한다.

## 3. 연결과 인증

운영 endpoint는 명시한 private host의 HTTPS만 허용한다. user-info, query, fragment, public host를 포함한 base URL은 시작 단계에서 거부한다.

service JWT claim은 다음과 같다.

- algorithm: `RS256`
- `iss`: `MCP_JWT_ISSUER`
- `aud`: `MCP_JWT_AUDIENCE`
- `sub`: `backend-worker`
- `scope`: `jeju:mcp:invoke`
- `iat`, `exp`, 고유 `jti`, `kid`: 필수
- 수명: 1초 이상 5분 이하, 기본 2분

토큰은 WebClient filter가 MCP HTTP 요청마다 새로 만든다. 사용자 access/refresh token을 재사용하지 않는다. 환경값에는 key를 넣지 않고 `kid`와 mount된 PKCS#8 PEM 절대 경로만 담은 descriptor file 경로를 둔다. issuer는 매 발급 시 descriptor를 다시 읽으므로 프로세스 재시작 없이 key를 교체하며, descriptor나 key가 비정상이면 이전 값을 묵시적으로 재사용하지 않고 발급을 fail-closed한다.

무중단 key rotation 순서는 다음과 같다.

1. AI JWKS에 old/new public key를 함께 게시한다.
2. immutable한 새 private PEM을 mount한다.
3. `{"kid":"new-kid","privateKeyFile":"/absolute/path/new.pem"}` descriptor를 임시 파일로 완성한 뒤 atomic rename으로 교체한다.
4. 최대 JWT 수명 5분 이상 지난 뒤 old public key를 JWKS에서 제거한다.

각 tool의 Pydantic input schema가 요구하는 `requestId`는 arguments 안에서 검증되고 `mcpInputHash` 계산에도 포함된다. Spring 요청 처리 중 생성한 canonical 32자리 lowercase hex trace ID가 있으면 같은 값을 private HTTP의 `X-Trace-Id`로 전파한다. 클라이언트가 보낸 trace 값이나 형식이 틀린 MDC 값은 전달하지 않는다.

## 4. 시작 단계 fail-closed

Spring AI가 `initialize`를 완료한 뒤 BE가 `tools/list`를 호출한다. 다음 중 하나면 application readiness를 올리지 않고 시작을 실패시킨다.

- 도구 집합이 정확히 여섯 개가 아님
- input/output schema의 canonical SHA-256가 manifest와 다름
- output schema 자체를 Draft 2020-12 validator로 compile할 수 없음

canonical fingerprint는 UTF-8 JSON key 정렬, 공백 없는 JSON 표현의 SHA-256이다. stdio와 Streamable HTTP는 같은 manifest checksum을 가져야 한다.

## 5. 호출 검증

각 `tools/call`은 다음 순서를 따른다.

1. domain arguments에 Pydantic 계약의 `requestId`를 추가한다.
2. `inputHash`를 제외한 실제 wire arguments canonical JSON에서 `mcpInputHash`를 계산한다.
3. 계산값을 `inputHash`로 추가하고 발견 시점 input schema로 검증한다.
4. schema의 모든 ID field와 정확히 같은 key set을 가진 outbound allowlist를 검사한다. 비거나 일부만 있는 allowlist는 거부한다.
5. 공식 SDK `callTool`을 실행한다. AI는 tool 실행 전에 같은 canonical hash를 다시 계산해 `inputHash`와 비교한다.
6. `isError=true`, 없는/비객체 `structuredContent`를 거부한다.
7. 발견 시점 output schema로 `structuredContent`를 검증한다.
8. schema ID field와 정확히 대응하는 inbound allowlist를 검사한다.
9. worker가 evidence closure와 도메인 invariant를 추가 검증한 뒤 transaction으로 저장한다.

intake의 immutable `commandInputHash`와 3단계의 `mcpInputHash`는 의미가 다르며 서로 덮어쓰지 않는다.

## 6. 데이터 경계

private MCP는 `jeju_AI/config/data_sources.toml`에 승인된 데이터 소스만 사용한다. BE는 다음 값을 MCP payload, DB artifact, call log, application log에 남기지 않는다.

- 사용자 요청 원문과 자유형 instruction
- 사용자 JWT, MCP JWT, provider credential
- TMAP 원본 응답과 상세 geometry
- TAGO/TourAPI provider 원본 body
- 지도 SDK의 정밀 현재 위치

BE가 영속화할 수 있는 계산 데이터는 schema 검증된 `structuredContent` artifact와 evidence ID뿐이다. `mcp_compute_call_logs`에는 parent run, tool name, contract/schema checksum, `commandInputHash`, `mcpInputHash`, fact count, attempt, status, latency, stable error code만 저장한다.

## 7. 실패 분류

| 분류 | run 처리 |
| --- | --- |
| `insufficient_feasible_routes`, `data_unavailable`, `unverifiable` | `status=succeeded`, domain outcome 유지, candidate 없음 |
| MCP 인증/transport/timeout/isError | retry policy 후 `status=failed` |
| schema checksum/JSON Schema/unknown ID/evidence closure | 재시도 없이 `status=failed`, 결과 저장 금지 |
| stale/없는 same-day TAGO | live 성공으로 승격하지 않음 |

실패는 기존 active schedule이나 마지막 성공 live snapshot을 덮지 않는다.

## 8. 운영 설정

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: false
app:
  mcp:
    enabled: true
    base-url: https://timing-jeju-ai:8000
    allowed-host: timing-jeju-ai
    issuer: timing-jeju-spring
    audience: timing-jeju-mcp
    subject: backend-worker
    scope: jeju:mcp:invoke
    signing-key-descriptor-file: ${MCP_JWT_SIGNING_KEY_DESCRIPTOR_FILE}
    token-lifetime: 2m
    request-timeout: 35s
    max-attempts: 3
    retry-delay: 200ms
    circuit-failure-threshold: 5
    circuit-open-duration: 30s
```

starter의 자동 client 생성은 끈다. BE는 공식 Spring AI `WebClientStreamableHttpTransport`와 Java MCP SDK `McpSyncClient`를 직접 bean으로 구성해 요청마다 service JWT를 발급하고 client lifecycle을 Spring context에 묶는다. `/mcp` 외 경로와 설정된 private host 외 endpoint는 허용하지 않는다.

AI process의 liveness는 `/health`, 계약 readiness는 `/ready`다. BE는 이 둘을 public proxy하지 않고 자체 `Actuator health`에 tools/list schema 검증을 마친 client readiness만 포함한다.

timeout과 일시적 transport 오류는 한 논리 호출에서 최대 3회까지 제한 재시도한다. `isError=true`, 인증, protocol, JSON Schema, checksum, ID allowlist 오류는 재시도하지 않는다. 논리 호출 5회가 연속 실패하면 30초 동안 circuit을 열고, 이후 단일 half-open 호출이 성공해야 닫는다. OPEN 이전 epoch에서 시작한 in-flight 성공은 OPEN을 닫을 수 없다. 각 실패 attempt와 최종 성공/계약 실패는 즉시 payload-free call log에 별도 행으로 기록한다. 실패 audit writer 장애는 `mcp.client.audit.failure{outcome=write_failed}`로 계수하되 원래 MCP 오류의 재시도 가능 여부와 stable code를 바꾸지 않는다. 최종 성공 audit 저장 실패는 성공을 반환하지 않고 fail-closed한다.

조건부 live 통합 테스트는 deterministic AI fixture가 반환할 `place_id`, `source_id`, `publication_id`, `source_fact_id` 전체를 `MCP_LIVE_EXPECTED_*_IDS` 환경값으로 명시한다. 빈 allowlist나 실행 결과에서 처음 발견한 ID를 사후 허용하는 방식은 금지한다.

### 8.1 deterministic TLS acceptance

외부 provider key가 없어도 실제 프로세스·프로토콜 경계는 다음 조건으로 검증한다.

- 실제 `jeju_AI`의 `jeju_trip.interfaces.mcp.http_server`를 `127.0.0.1:18443`에서 실행한다.
- 테스트 전용 self-signed TLS 인증서, RS256 private key, local JWKS와 signing descriptor는 임시 owner-only 디렉터리에 생성하며 저장소에 커밋하지 않는다.
- TLS 인증서는 테스트 전용 PKCS12 truststore로 가져오고 Gradle JVM에는 `javax.net.ssl.trustStore`, `javax.net.ssl.trustStorePassword`, `javax.net.ssl.trustStoreType=PKCS12`를 전달한다.
- Spring 테스트에는 `MCP_LIVE_TEST=true`, 같은 issuer/audience, descriptor 절대 경로와 사전에 확정한 네 ID allowlist를 전달한다.
- Docker가 실행 중이어야 하며 테스트는 H2가 아니라 전체 migration이 적용된 PostgreSQL Testcontainers를 사용한다.
- owner부터 `compute_runs`까지의 parent graph를 transaction 안에서 준비하고 `SET CONSTRAINTS ALL IMMEDIATE`로 deferred constraint trigger까지 호출 전에 평가한다. 실제 `mcp_compute_call_logs` insert에도 외래키·CHECK·trigger를 모두 적용하고 테스트 종료 시 transaction rollback으로 제거한다. 별도 음수 테스트는 active schedule pointer가 불일치한 parent를 같은 강제 평가 시점에 거부하는지 확인한다.

다음 절차는 macOS/Linux의 격리된 로컬 환경을 기준으로 한다. 저장소 경로만 바꾸고, 실제 provider credential은 사용하거나 명령 기록에 넣지 않는다.

1. owner-only 임시 디렉터리에 TLS key/certificate와 RS256 signing key를 만든다.

```bash
umask 077
export LIVE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/jeju-mcp-live.XXXXXX")"
export MCP_LIVE_ISSUER=https://timing-jeju-spring.test
export MCP_LIVE_AUDIENCE=https://timing-jeju-mcp.test
export MCP_LIVE_KID=issue202-live-key
export MCP_LIVE_TRUSTSTORE_PASSWORD="$(openssl rand -hex 16)"
export TRUSTSTORE_PASSWORD_PROPERTY=javax.net.ssl.trustStorePassword

openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj '/CN=127.0.0.1' -addext 'subjectAltName=IP:127.0.0.1' \
  -keyout "$LIVE_DIR/tls.key" -out "$LIVE_DIR/tls.crt"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$LIVE_DIR/signing.pem"
chmod 600 "$LIVE_DIR/tls.key" "$LIVE_DIR/tls.crt" "$LIVE_DIR/signing.pem"
```

2. `jeju_AI` dependency 환경의 `cryptography`로 public JWK/JWKS와 Spring signing descriptor를 만든다. heredoc은 환경에 있는 key를 읽어 공개 modulus/exponent만 JWKS에 쓰며 private key 본문을 출력하지 않는다.

```bash
cd /absolute/path/to/jeju_AI
uv run python - <<'PY'
import base64
import json
import os
from pathlib import Path
from cryptography.hazmat.primitives import serialization

root = Path(os.environ["LIVE_DIR"])
private_key = serialization.load_pem_private_key(
    (root / "signing.pem").read_bytes(), password=None
)
numbers = private_key.public_key().public_numbers()

def base64url_uint(value: int) -> str:
    raw = value.to_bytes((value.bit_length() + 7) // 8, "big")
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")

kid = os.environ["MCP_LIVE_KID"]
(root / "jwks.json").write_text(
    json.dumps({"keys": [{
        "kty": "RSA", "use": "sig", "alg": "RS256", "kid": kid,
        "n": base64url_uint(numbers.n), "e": base64url_uint(numbers.e),
    }]}, separators=(",", ":")),
    encoding="utf-8",
)
(root / "descriptor.json").write_text(
    json.dumps({"kid": kid, "privateKeyFile": str(root / "signing.pem")}),
    encoding="utf-8",
)
PY

keytool -importcert -noprompt -alias issue202-live \
  -file "$LIVE_DIR/tls.crt" -keystore "$LIVE_DIR/truststore.p12" \
  -storetype PKCS12 -storepass "$MCP_LIVE_TRUSTSTORE_PASSWORD"
chmod 600 "$LIVE_DIR/jwks.json" "$LIVE_DIR/descriptor.json" "$LIVE_DIR/truststore.p12"
```

3. 같은 terminal에서 실제 AI HTTP server를 loopback으로 기동한다. deterministic acceptance에서는 의도적으로 `JEJU_RUNTIME_DSN`, `JEJU_TMAP_API_KEY`, `JEJU_TAGO_SERVICE_KEY`를 설정하지 않는다. 세 runtime 값이 필수인 운영용 `jeju-trip-mcp-http` launcher 대신 실제 `http_server` module을 직접 실행하면 승인되지 않은 provider를 호출하지 않는 disabled gateway가 `data_unavailable` structured result를 반환한다.

```bash
cd /absolute/path/to/jeju_AI
JEJU_MCP_HTTP_HOST=127.0.0.1 \
JEJU_MCP_HTTP_PORT=18443 \
JEJU_MCP_AUTH_ISSUER="$MCP_LIVE_ISSUER" \
JEJU_MCP_AUTH_AUDIENCE="$MCP_LIVE_AUDIENCE" \
JEJU_MCP_AUTH_JWKS_FILE="$LIVE_DIR/jwks.json" \
JEJU_MCP_TLS_CERT_FILE="$LIVE_DIR/tls.crt" \
JEJU_MCP_TLS_KEY_FILE="$LIVE_DIR/tls.key" \
uv run python -m jeju_trip.interfaces.mcp.http_server \
  >"$LIVE_DIR/ai-server.log" 2>&1 &
export MCP_LIVE_AI_PID=$!

curl --fail --silent --show-error --cacert "$LIVE_DIR/tls.crt" \
  https://127.0.0.1:18443/health
curl --fail --silent --show-error --cacert "$LIVE_DIR/tls.crt" \
  https://127.0.0.1:18443/ready
```

응답은 각각 `{"status":"ok"}`와 `{"status":"ready","contractVersion":"0.7.0"}`이어야 한다.

4. 같은 terminal에서 Spring live test를 실행한다. 예시 ID는 호출 전에 고정된 allowlist이며 결과에서 동적으로 추출하지 않는다.

```bash
MCP_LIVE_TEST=true \
MCP_JWT_ISSUER="$MCP_LIVE_ISSUER" \
MCP_JWT_AUDIENCE="$MCP_LIVE_AUDIENCE" \
MCP_JWT_SIGNING_KEY_DESCRIPTOR_FILE="$LIVE_DIR/descriptor.json" \
MCP_LIVE_EXPECTED_PLACE_IDS=preapproved-place-id \
MCP_LIVE_EXPECTED_SOURCE_IDS=preapproved-source-id \
MCP_LIVE_EXPECTED_PUBLICATION_IDS=preapproved-publication-id \
MCP_LIVE_EXPECTED_SOURCE_FACT_IDS=preapproved-fact-id \
JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$LIVE_DIR/truststore.p12 -D${TRUSTSTORE_PASSWORD_PROPERTY}=$MCP_LIVE_TRUSTSTORE_PASSWORD -Djavax.net.ssl.trustStoreType=PKCS12" \
/absolute/path/to/jeju_BE/services/spring-api/gradlew \
  -p /absolute/path/to/jeju_BE/services/spring-api --no-daemon test \
  --tests 'com.timingjeju.api.global.mcp.McpPrivateHttpIntegrationTest'
```

이 acceptance는 `/health`, `/ready`, TLS handshake, RS256 service JWT, initialize, 정확한 여섯 도구의 `tools/list`와 schema checksum, 최소 한 번의 `tools/call`, PostgreSQL 감사 저장을 함께 확인한다. 인증·schema·ID allowlist의 음수 경로는 `com.timingjeju.api.global.mcp.*` 테스트가 fail-closed를 검증한다.

5. 종료 후 process와 임시 key material을 정리한다. `LIVE_DIR`가 이 절차에서 만든 정확한 prefix인지 검사한 뒤에만 삭제한다.

```bash
kill "$MCP_LIVE_AI_PID"
wait "$MCP_LIVE_AI_PID" || true
python3 - <<'PY'
import os
import shutil
from pathlib import Path

path = Path(os.environ["LIVE_DIR"]).resolve()
temporary_root = Path(os.environ.get("TMPDIR", "/tmp")).resolve()
if path.parent != temporary_root or not path.name.startswith("jeju-mcp-live."):
    raise SystemExit("unexpected LIVE_DIR; refusing cleanup")
shutil.rmtree(path)
PY
unset MCP_LIVE_AI_PID LIVE_DIR MCP_LIVE_ISSUER MCP_LIVE_AUDIENCE MCP_LIVE_KID
unset MCP_LIVE_TRUSTSTORE_PASSWORD TRUSTSTORE_PASSWORD_PROPERTY
```

### 8.2 provider staging acceptance

실제 runtime DB·TMAP·TAGO가 필요한 검증은 deterministic acceptance와 분리한다. 승인된 staging role의 `JEJU_RUNTIME_DSN`과 필요한 provider key가 모두 주입된 격리 환경에서만 실행하며, 하나라도 없으면 deterministic 결과를 실패로 바꾸지 않고 `SKIPPED`와 누락 capability만 기록한다. 사용자 원문, JWT, provider body와 TMAP geometry는 결과나 로그에 기록하지 않는다.

Actuator health에는 schema 검증까지 끝난 readiness만 노출한다. metric tag는 `tool`, `status`처럼 닫힌 저카디널리티 값만 허용하며 trip/user/request ID나 오류 원문을 tag로 사용하지 않는다.

## 9. 선행조건 변경

[ADR-0052](../adr/0052-private-mcp-data-ownership.md)에 따라 #31과 #62는 BE 자체 provider 기능으로 유지하지만 planner #52의 선행조건이 아니다. planner 경로에서는 BE가 TMAP/TAGO 원문을 소유하지 않는다.
