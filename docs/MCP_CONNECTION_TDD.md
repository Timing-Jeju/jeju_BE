# #267 연결 설정 검증 기록

## 범위

2026-09-16 KST, 최신 develop `36a4bc4c`에서 시작했다. 작업은 MCP 전용 TLS 신뢰 파일,
두 클라이언트의 connector, Compose 환경 전달과 인계 문서에 한정했다.
AWS, 배포, 실키·인증서, FE/UI, DB migration과 공개 계약을 변경하지 않았다.

## Red

- `./gradlew --no-daemon test --tests '*McpTlsHttpClientTest'`
  - 운영 코드 추가 전에 `McpTlsHttpClient` 심볼이 없어 compileTestJava 실패.
- `python3 -m unittest scripts.tests.test_mcp_connection_settings`
  - Compose MCP 환경 전달 14개와 신규 TLS property 누락으로 실패.

## Green / Refactor

- `./gradlew --no-daemon spotlessApply test --tests '*McpTls*'` 성공.
- Spring context 테스트를 추가한 첫 실행은 경량 ApplicationContextRunner에 Boot의
  Duration conversion이 없어 실패했다. 테스트에 실제 Boot의 ApplicationConversionService를
  등록한 뒤 재실행했으며 운영 timeout 정책은 변경하지 않았다.
- `./gradlew --no-daemon spotlessApply test --tests '*global.mcp.*'` 성공: 85 tests.
- `python3 -m unittest scripts.tests.test_mcp_connection_settings` 성공: 2 tests.
- `git diff --check` 성공.

검증은 실행 시 임시 생성한 TLS/JWT 키와 localhost 서버만 사용한다. 일반·생성 클라이언트
각각 initialize, tools/list, tools/call 및 서비스 JWT 서명/issuer/audience/scope를 검증했다.
미신뢰 chain 및 SAN mismatch는 SSLHandshakeException과 요청 도달 0건을 확인했다.
파일 누락·빈 내용·malformed·상대 경로·디렉터리·64 KiB 초과는 고정 오류로 거부한다.
기본 신뢰와 JVM 전역 SSLContext 불변, 비활성 시 무생성, 생성 165초 경계도 검사했다.
두 transport의 공통 HTTP 설정은 Spring context에서 별도로 검증했다.

## 완료와 구분할 항목

위의 관련 테스트 성공은 전체 품질 게이트 성공을 뜻하지 않는다. 커밋 후 동일 SHA의
전체 품질 게이트/Docker는 pre-push에서 백그라운드 실행하고, 독립 pre-pr-review 및
승인 기록이 완료된 후에만 create-pr를 실행한다. 실제 실행 결과는 로컬 품질 게이트
기록과 해당 GitHub Issue/PR 상태에서 확인한다. 승인이나 결과 파일을 대신 생성하지 않는다.
운영 DNS·통신·JWT 키 등록 및 AWS MCP와의 종단 검증은 배포 담당자의 후속 작업이다.
