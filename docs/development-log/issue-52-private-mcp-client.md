# Issue #52 Spring–FastAPI private MCP client 개발 일지

## 범위

- 외부 공개 Controller 없이 내부 `McpToolClient` port와 Spring AI Streamable HTTP adapter를 제공한다.
- jeju_AI Pydantic `0.7.0` release manifest를 schema checksum의 유일한 원본으로 사용한다.
- 사용자 JWT, 외부 API key, 사용자 원문, provider raw payload, TMAP geometry를 전송·저장·로그하지 않는다.
- `commandInputHash`와 실제 wire arguments의 `mcpInputHash`를 분리한다.

## 실행 Gate와 TDD 증거

### 실행 Gate 사후 예외

- 최초 운영 구현 커밋 `34c1e3f`의 author/committer 시각은 2026-09-01 08:56:05 KST다.
- #31은 2026-09-02 13:43:05 KST, #62는 2026-09-02 14:39:19 KST에 CLOSED됐고, #114는 2026-08-29 08:50:45 KST에 CLOSED됐다.
- 따라서 #31과 #62가 OPEN인 동안 구현을 시작해 Issue의 실행 Gate Acceptance Criteria를 위반했다. 이전 기록의 “모두 CLOSED 후에만 구현 시작” 진술은 사실과 달라 이 절에서 정정한다.
- 2026-09-03 Owner/PM은 위 시각과 위반 사실을 확인하고 Issue #52에 한정한 사후 예외(waiver)를 명시적으로 승인했다. 이 예외는 다른 Issue의 실행 Gate를 완화하지 않는다.
- 세 Gate가 모두 CLOSED된 뒤 최신 `origin/develop`을 반영하고 전체 품질 Gate와 독립 리뷰를 다시 수행한다.

### 시간순 RED → GREEN

| 시각/기준 | 시나리오와 실행 명령 | RED 핵심 실패 | GREEN/Refactor 결과 |
| --- | --- | --- | --- |
| 실행 Gate 감사 | `git log --reverse origin/develop..HEAD`와 `gh issue view 31/62/114` | `34c1e3f`가 #31·#62 종료보다 먼저여서 시작 금지 AC 위반을 확인했다. | 위반 사실과 Owner/PM의 Issue #52 한정 사후 예외를 기록하고, Gate 종료 후 최신 develop 반영·전체 검증·독립 리뷰를 요구한다. |
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
