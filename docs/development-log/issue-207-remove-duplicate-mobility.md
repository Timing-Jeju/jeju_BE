# Issue #207 Spring mobility 중복 제거 개발 일지

## 범위와 기준

- 기준 브랜치: `origin/develop` (`6cfa98fd3e65ba270eceea7150c843b33dbe2a56`)
- 작업 브랜치: `refactor/207-remove-duplicate-mobility`
- AI의 TMAP route fact·TTL cache·fallback을 단일 계산 경계로 두고, 운영 소비자가 없는 Spring
  `application.mobility`를 제거했다.
- Spring의 공개 REST, migration, async run, TourAPI·TAGO·KMA 적재와
  `global/mcp`의 private client/JWT/TLS/schema guard/audit는 변경하지 않았다.

## Red

운영 코드보다 먼저 `MobilityOwnershipContractTest`를 추가했다. 테스트는 Spring mobility 운영
소스와 전용 테스트·계약, #41 Architecture 규칙이 없어야 하며 AI와 Spring의 책임 문구가 하나로
정렬돼야 한다고 선언했다. 동시에 공개 Controller 11개, mapping annotation 37개, migration
35개와 핵심 MCP 보안·계약·감사 파일을 보존 inventory로 고정했다.

```bash
cd services/spring-api
./gradlew --no-daemon architectureTest \
  --tests 'com.timingjeju.api.architecture.MobilityOwnershipContractTest'
```

결과: 2개 중 1개 실패. 첫 assertion이 `application/mobility`의 운영 Java 파일 12개를 발견해
의도한 이유로 Red가 됐고, 보존 inventory 테스트는 통과했다.

## Green과 Refactor

- Spring mobility 운영 Java 12개와 전용 테스트 2개를 제거했다.
- `ArchitectureTest`의 #41 package 전용 규칙을 제거했다.
- Spring mobility 전용 계약과 과거 구현 일지를 제거했다.
- ADR, 아키텍처, Spring–MCP 명세와 외부 API·DB 설계 문서를 AI route runtime 단일 소유 및
  Spring MCP 연결·계약 검증·감사·제품 DB 결과 저장 소유로 정렬했다.
- #40의 역사적 결정 문서는 #41 Spring port 결정이 #207로 대체됐음을 명시했다.

같은 ownership 테스트는 2개 모두 통과했다. Refactor 후 전체 Architecture 테스트 29개와 실제
DB/live HTTP를 제외한 MCP 테스트 22개가 통과했고 운영·테스트 소스 컴파일도 성공했다.

## 검증

```bash
cd services/spring-api
./gradlew --no-daemon spotlessApply compileJava compileTestJava
./gradlew --no-daemon architectureTest
./gradlew --no-daemon test \
  --tests 'com.timingjeju.api.global.mcp.McpCallResilienceTest' \
  --tests 'com.timingjeju.api.global.mcp.McpContractGuardTest' \
  --tests 'com.timingjeju.api.global.mcp.McpEndpointPolicyTest' \
  --tests 'com.timingjeju.api.global.mcp.McpExpectedCatalogTest' \
  --tests 'com.timingjeju.api.global.mcp.McpFailureClassifierTest' \
  --tests 'com.timingjeju.api.global.mcp.McpHealthIndicatorTest' \
  --tests 'com.timingjeju.api.global.mcp.McpPemPrivateKeyLoaderTest' \
  --tests 'com.timingjeju.api.global.mcp.McpPrivateRequestFilterTest' \
  --tests 'com.timingjeju.api.global.mcp.McpServiceJwtIssuerTest' \
  --tests 'com.timingjeju.api.global.mcp.PlannerFeIntegrationContractTest' \
  --tests 'com.timingjeju.api.global.mcp.ReloadingMcpSigningKeyProviderTest' \
  --tests 'com.timingjeju.api.global.mcp.SpringAiJejuMcpClientTest'
python3 scripts/git-hooks/scan-staged-secrets.py --all-files
git diff --check
```

Issue #207의 승인 범위에 따라 실제 DB/Postgres/Testcontainers, Docker, live MCP/Supabase와 전체
heavy quality gate는 실행하지 않았다. 따라서 이 변경은 해당 검증이 별도 승인 아래 완료되기 전
`READY_FOR_REVIEW`로 선언하지 않는다.

## 2026-09-05 전체 품질 게이트

- Red: #195 포트 격리를 병합한 뒤 quality gate의 저장소 자동화 704건 중 1건이 실패했다.
  TMAP 계약 테스트가 #207 이전의 `TMAP 저장 금지; #40 DEFER 경계` 문구를 요구해,
  현재 canonical인 `Spring 신규 writer 없음; TMAP 저장 금지`와 어긋났다.
- Green: 테스트를 현재 AI runtime memory-only·Spring writer 0 소유권 문구에 맞추되,
  TMAP 원문·geometry·개별 metric 비영속 assertion은 그대로 보존했다.
