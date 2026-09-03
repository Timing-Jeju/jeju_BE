# Issue #202 Spring–FastAPI 실제 TLS MCP 종단 검증

## 범위

- 실제 `jeju_AI` v0.7 Streamable HTTP process와 Spring client를 TLS·RS256으로 연결한다.
- live 테스트를 전체 migration이 적용된 PostgreSQL Testcontainers에서 실행한다.
- 유효한 compute parent와 payload-free MCP 감사 로그를 실제 외래키·CHECK·trigger 아래 검증한다.
- 외부 provider key가 필요 없는 deterministic transport acceptance와 provider staging acceptance를 분리한다.

## RED → GREEN

| 단계 | 실행·관찰 | 결과 |
| --- | --- | --- |
| RED | 실제 AI `/health`, `/ready`, `ListToolsRequest`, `CallToolRequest`까지 도달한 상태에서 기존 `McpPrivateHttpIntegrationTest` 실행 | H2에는 `public.mcp_compute_call_logs`가 없어 성공 응답 뒤 audit writer가 실패했고 Spring이 `MCP_INTERNAL_ERROR`를 반환했다. |
| GREEN 1 | 테스트에 `postgresql-integration` profile과 `PostgreSqlTestcontainersConfiguration` 적용 | 전체 migration 위에서 audit table과 제약을 사용했다. |
| GREEN 2 | transaction 안에 owner·trip·day·schedule·queued `compute_runs` 전체 parent graph를 만든 뒤 실제 MCP 호출 | fixture와 audit insert가 모두 활성 외래키·CHECK·trigger를 통과했고 정확한 parent, tool, status, 두 hash와 schema checksum을 확인했다. |
| 실제 종단 | 실제 AI TLS server와 test-only JWKS/signing descriptor/truststore로 live test 실행 | `/health` 성공, `contractVersion=0.7.0`, `ListToolsRequest`와 `CallToolRequest` 확인, Gradle `BUILD SUCCESSFUL`. |
| 리뷰 RED | 최초 구현은 test transaction rollback 전에 deferred active-schedule constraint trigger를 강제하지 않았고, 문서는 Spring 실행 절반만 제공했다. | 독립 Reviewer가 MAJOR 2건으로 `CHANGES_REQUESTED`했다. |
| 리뷰 GREEN | 정상 parent graph 뒤 `SET CONSTRAINTS ALL IMMEDIATE`, planned trip의 active pointer 누락 음수 fixture, owner-only key 생성부터 cleanup까지 전체 절차를 추가했다. | 새 임시 디렉터리에서 문서 절차로 `/health`, `/ready`, initialize/list/call/audit와 정상·음수 deferred trigger 테스트가 모두 성공했다. |

test-only 인증서와 key material은 임시 디렉터리에서만 사용하고 저장소에는 추가하지 않았다. 호출·감사 assertion에는 사용자 원문, JWT, provider raw response와 TMAP geometry가 없다.

## 검증 명령

```bash
./gradlew --no-daemon test --tests 'com.timingjeju.api.global.mcp.*'
./gradlew --no-daemon integrationTest --tests 'com.timingjeju.api.support.postgresql.McpCallLogSchemaIntegrationTest'
```

조건부 실제 TLS 실행에 필요한 환경과 truststore 절차는 [Spring–MCP private HTTP 연동 명세](../designs/timing-jeju-spring-fastapi-integration-contract.md#81-deterministic-tls-acceptance)를 따른다.

## staging 상태

현재 로컬 환경에는 승인된 `JEJU_RUNTIME_DSN`, `JEJU_TMAP_API_KEY`, `JEJU_TAGO_SERVICE_KEY`가 없어 provider staging acceptance는 `SKIPPED`다. 이는 실제 TLS·JWT·MCP·PostgreSQL deterministic acceptance의 성공과 분리한다.
