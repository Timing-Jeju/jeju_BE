# Issue #52 Spring–FastAPI private MCP client 개발 일지

## 범위

- 외부 공개 Controller 없이 내부 `McpToolClient` port와 Spring AI Streamable HTTP adapter를 제공한다.
- jeju_AI Pydantic `0.7.0` release manifest를 schema checksum의 유일한 원본으로 사용한다.
- 사용자 JWT, 외부 API key, 사용자 원문, provider raw payload, TMAP geometry를 전송·저장·로그하지 않는다.
- `commandInputHash`와 실제 wire arguments의 `mcpInputHash`를 분리한다.

## 실행 Gate와 TDD 증거

### 시간순 RED → GREEN

| 시각/기준 | 시나리오와 실행 명령 | RED 핵심 실패 | GREEN/Refactor 결과 |
| --- | --- | --- | --- |
| 개발 보류 시점 | `gh issue view 31`, `gh issue view 62`, `gh issue view 114` | 세 실행 Gate 중 OPEN이 있어 브랜치 생성·운영 코드 변경·PR 생성을 중단했다. | 2026-09-02 세 이슈가 모두 CLOSED임을 재조회한 뒤에만 `feat/52-spring-fastapi-mcp-client` 작업을 시작했다. |
| 최초 경계 | `./gradlew --no-daemon test --tests 'com.timingjeju.api.global.mcp.McpPrivateRequestFilterTest'` | `compileTestJava FAILED`: `McpPrivateRequestFilter`가 없어 service JWT/trace 전파 테스트를 컴파일할 수 없었다. | 같은 명령 `BUILD SUCCESSFUL`; 매 요청 새 JWT와 canonical trace만 전파한다. |
| auth 401·timeout·malformed JSON-RPC | `./gradlew --no-daemon test --tests 'com.timingjeju.api.global.mcp.McpFailureClassifierTest'` | test-first working tree에서 `McpFailureClassifier`/stable retry metadata가 없어 `compileTestJava FAILED`였다. | `MCP_AUTHENTICATION_FAILED` non-retry, `MCP_TIMEOUT` retry, `MCP_PROTOCOL_INVALID` non-retry를 raw cause 없이 검증했다. |
| schema mismatch | `./gradlew --no-daemon test --tests 'com.timingjeju.api.global.mcp.McpContractGuardTest.발견한_도구가_빠지거나_schema_checksum이_다르면_초기화를_닫힌_실패로_중단한다'` | tools/list 누락·checksum mismatch가 시작을 중단하지 않아 “throwable expected”로 실패했다. | Pydantic manifest와 발견 schema fingerprint가 다르면 `MCP_CONTRACT_INVALID`로 readiness를 열지 않는다. |
| unknown ID | `./gradlew --no-daemon test --tests 'com.timingjeju.api.global.mcp.McpContractGuardTest.ID_allowlist는_schema의_모든_ID_field를_선언하고_text값만_허용한다'` | 빈/부분 allowlist가 우회되어 “throwable expected”로 실패했다. | schema의 모든 ID field와 allowlist key set 완전 일치 및 textual ID만 허용한다. |
| redaction/audit | `./gradlew --no-daemon integrationTest --tests 'com.timingjeju.api.support.postgresql.McpCallLogSchemaIntegrationTest'` | runtime writer가 없고 기존 table에 user/trip/payload/error 원문 column이 남아 `compileTestJava`와 column assertion이 실패했다. | attempt별 두 hash/count/status/latency/stable code만 기록하며 금지 column 부재를 실제 PostgreSQL에서 검증했다. |
| wire integrity | `./gradlew --no-daemon test --tests 'com.timingjeju.api.global.mcp.SpringAiJejuMcpClientTest'` | SDK arguments에 `requestId`/`inputHash`가 없고 AI가 실행 전 hash를 확인하지 않아 wire assertion이 실패했다. | jeju_AI PR #13과 동일한 canonical arguments/hash를 양쪽에서 검증한다. |
| 2026-09-03 `e6f3f6f4` | `./gradlew --no-daemon test --tests 'com.timingjeju.api.global.mcp.McpCallResilienceTest.open_이전_inflight_성공은_open_circuit을_닫지_못한다'` | line 98: OPEN 뒤 호출에서 예외를 기대했지만 stale 성공이 circuit을 CLOSED로 되돌려 실패했다. | epoch permit fencing 후 같은 명령 `BUILD SUCCESSFUL`. |
| 2026-09-03 `e6f3f6f4` | `./gradlew --no-daemon test --tests 'com.timingjeju.api.global.mcp.SpringAiJejuMcpClientTest.인증_protocol_실패_audit_writer가_실패해도_MCP를_재호출하지_않고_원래_code를_유지한다'` | 최초 이름이 `인증_실패_...`일 때 expected `MCP_AUTHENTICATION_FAILED`, actual `MCP_INTERNAL_ERROR`; audit DB 예외가 원래 분류를 바꿨다. | 실패 audit 경계를 분리한 뒤 auth 401과 malformed protocol을 함께 실행해 `BUILD SUCCESSFUL`; 각각 MCP `callTool` 정확히 1회 및 cause-free 원래 code 유지. |

Refactor 후 `./gradlew --no-daemon test --tests 'com.timingjeju.api.global.mcp.*'`와 PostgreSQL `McpCallLogSchemaIntegrationTest`, 루트 `quality-gate.sh`를 같은 HEAD에서 다시 실행한다. live private TLS 통합 테스트는 실제 jeju_AI, test key, 사전 확정된 `MCP_LIVE_EXPECTED_*_IDS` fixture가 제공된 격리 환경에서만 `MCP_LIVE_TEST=true`로 실행한다.

## 운영 경계

- endpoint는 명시한 private host의 HTTPS `/mcp`만 허용한다.
- 감사 테이블에는 parent run, tool, contract/schema checksum, 두 hash, fact count, attempt, status, latency와 stable error code만 둔다.
- 감사 테이블에서 user/trip 직접 식별자와 payload column을 제거하고 새 request ID와 status/error 조합을 DB constraint로 제한한다.
- token, payload, 원본 오류 message와 고카디널리티 식별자는 application log와 metric tag에 남기지 않는다.
