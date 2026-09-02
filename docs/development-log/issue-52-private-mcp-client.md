# Issue #52 Spring–FastAPI private MCP client 개발 일지

## 범위

- 외부 공개 Controller 없이 내부 `McpToolClient` port와 Spring AI Streamable HTTP adapter를 제공한다.
- jeju_AI Pydantic `0.7.0` release manifest를 schema checksum의 유일한 원본으로 사용한다.
- 사용자 JWT, 외부 API key, 사용자 원문, provider raw payload, TMAP geometry를 전송·저장·로그하지 않는다.
- `commandInputHash`와 실제 wire arguments의 `mcpInputHash`를 분리한다.

## 실행 Gate와 TDD 증거

- 2026-09-02 확인: #31, #62, #114 모두 CLOSED.
- RED: `./gradlew --no-daemon test --tests 'com.timingjeju.api.global.mcp.McpPrivateRequestFilterTest'`
  - 실패 원인: private HTTP 요청에 service JWT와 서버 trace ID를 함께 적용하는 `McpPrivateRequestFilter`가 없었다.
- GREEN: 같은 명령 성공.
  - 매 HTTP 요청마다 새 RS256 service JWT를 발급한다.
  - 서버가 생성한 32자리 lowercase hex trace ID만 `X-Trace-Id`로 전달하고 비정상 값은 제거한다.

- RED: AI Pydantic 계약에 `requestId`/`inputHash`가 없고 BE가 wire hash를 보내지 않아 end-to-end 무결성을 검증할 수 없었다.
- GREEN: jeju_AI PR #13에서 여섯 Pydantic input schema와 실행 전 canonical hash 검증을 추가했고, BE manifest와 wire arguments를 같은 계약으로 갱신했다.
- RED: 빈/부분 ID allowlist, runtime audit writer 부재, raw transport cause, 1초 미만 JWT와 static signing key가 허용됐다.
- GREEN: schema ID field와 allowlist key set의 완전 일치, attempt별 JDBC audit, stable failure classifier, 1초~5분 JWT 수명과 atomic descriptor key rotation을 추가했다.

기존 MCP 집중 테스트는 initialize, tools/list checksum, input/output JSON Schema, unknown ID, malformed `structuredContent`, bounded retry/circuit breaker, JWT claim/expiry, endpoint allowlist와 payload-free DB schema를 검증한다. live private TLS 통합 테스트는 실제 jeju_AI와 test key가 제공된 환경에서만 `MCP_LIVE_TEST=true`로 실행한다.

## 운영 경계

- endpoint는 명시한 private host의 HTTPS `/mcp`만 허용한다.
- 감사 테이블에는 parent run, tool, contract/schema checksum, 두 hash, fact count, attempt, status, latency와 stable error code만 둔다.
- 감사 테이블에서 user/trip 직접 식별자와 payload column을 제거하고 새 request ID와 status/error 조합을 DB constraint로 제한한다.
- token, payload, 원본 오류 message와 고카디널리티 식별자는 application log와 metric tag에 남기지 않는다.
