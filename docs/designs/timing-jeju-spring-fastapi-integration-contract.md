# 타이밍제주 Spring–MCP private HTTP 연동 명세 v2.0

## 1. 기준 계약

planner runtime 계약은 [`Timing-Jeju/jeju_AI`](https://github.com/Timing-Jeju/jeju_AI)의 Pydantic `0.7.0`이 유일한 원본이다. transport·인증·경계 설명은 AI의 [`docs/FASTAPI_MCP_CONTRACT.md`](https://github.com/Timing-Jeju/jeju_AI/blob/develop/docs/FASTAPI_MCP_CONTRACT.md)를 따른다. BE는 release artifact인 `mcp-tools-v0.7.json`만 포함하며 Pydantic schema를 Java DTO로 다시 정의하지 않는다.

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
- 수명: 5분 이하, 기본 2분

토큰은 WebClient filter가 MCP HTTP 요청마다 새로 만든다. 사용자 access/refresh token을 재사용하지 않는다. private key는 환경값이 아니라 mount된 PKCS#8 PEM file에서 읽는다.

각 tool의 Pydantic input schema가 요구하는 `requestId`는 arguments 안에서 검증되고 `mcpInputHash` 계산에도 포함된다. Spring 요청 처리 중 생성한 canonical 32자리 lowercase hex trace ID가 있으면 같은 값을 private HTTP의 `X-Trace-Id`로 전파한다. 클라이언트가 보낸 trace 값이나 형식이 틀린 MDC 값은 전달하지 않는다.

## 4. 시작 단계 fail-closed

Spring AI가 `initialize`를 완료한 뒤 BE가 `tools/list`를 호출한다. 다음 중 하나면 application readiness를 올리지 않고 시작을 실패시킨다.

- 도구 집합이 정확히 여섯 개가 아님
- input/output schema의 canonical SHA-256가 manifest와 다름
- output schema 자체를 Draft 2020-12 validator로 compile할 수 없음

canonical fingerprint는 UTF-8 JSON key 정렬, 공백 없는 JSON 표현의 SHA-256이다. stdio와 Streamable HTTP는 같은 manifest checksum을 가져야 한다.

## 5. 호출 검증

각 `tools/call`은 다음 순서를 따른다.

1. 실제 arguments를 발견 시점 input schema로 검증한다.
2. worker가 지정한 outbound field별 ID allowlist를 검사한다.
3. arguments canonical JSON에서 `mcpInputHash`를 계산한다.
4. 공식 SDK `callTool`을 실행한다.
5. `isError=true`, 없는/비객체 `structuredContent`를 거부한다.
6. 발견 시점 output schema로 `structuredContent`를 검증한다.
7. inbound field별 ID allowlist를 검사한다.
8. worker가 evidence closure와 도메인 invariant를 추가 검증한 뒤 transaction으로 저장한다.

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
    key-id: ${MCP_JWT_KEY_ID}
    private-key-file: ${MCP_JWT_PRIVATE_KEY_FILE}
    token-lifetime: 2m
    request-timeout: 35s
    max-attempts: 3
    retry-delay: 200ms
    circuit-failure-threshold: 5
    circuit-open-duration: 30s
```

starter의 자동 client 생성은 끈다. BE는 공식 Spring AI `WebClientStreamableHttpTransport`와 Java MCP SDK `McpSyncClient`를 직접 bean으로 구성해 요청마다 service JWT를 발급하고 client lifecycle을 Spring context에 묶는다. `/mcp` 외 경로와 설정된 private host 외 endpoint는 허용하지 않는다.

AI process의 liveness는 `/health`, 계약 readiness는 `/ready`다. BE는 이 둘을 public proxy하지 않고 자체 `Actuator health`에 tools/list schema 검증을 마친 client readiness만 포함한다.

transport와 `isError=true`는 한 논리 호출에서 최대 3회까지 제한 재시도한다. JSON Schema, checksum, ID allowlist 오류는 재시도하지 않는다. 논리 호출 5회가 연속 실패하면 30초 동안 circuit을 열고, 이후 단일 half-open 호출이 성공해야 닫는다. 실제 attempt 수는 결과 metadata로 worker에 전달해 payload-free call log의 `attempt_no`에 기록할 수 있게 한다.

Actuator health에는 schema 검증까지 끝난 readiness만 노출한다. metric tag는 `tool`, `status`처럼 닫힌 저카디널리티 값만 허용하며 trip/user/request ID나 오류 원문을 tag로 사용하지 않는다.

## 9. 선행조건 변경

[ADR-0052](../adr/0052-private-mcp-data-ownership.md)에 따라 #31과 #62는 BE 자체 provider 기능으로 유지하지만 planner #52의 선행조건이 아니다. planner 경로에서는 BE가 TMAP/TAGO 원문을 소유하지 않는다.
