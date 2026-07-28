# Spring API 에이전트 규칙

## 적용 범위

- 이 디렉터리는 Java 21, Spring Boot 4.1, Gradle Wrapper 기반 공개 API 서버다.
- 저장소 루트 `AGENTS.md`의 Issue·Git·리뷰·보안 규칙을 모두 상속한다.
- 코드는 `com.timingjeju.api.domain` 아래에 도메인별로 모으고 공통 관심사만 `global`에 둔다.

## 명령

- 로컬 실행: `./gradlew bootRun`
- 전체 검사: `./gradlew clean check`
- Architecture 검사: `./gradlew architectureTest`
- OpenAPI JSON 생성: `./gradlew openApiDocs`
- 루트 통합 품질 게이트: `../../scripts/quality-gate.sh`

## API 문서화

- 공개 `/api/v1/**`는 springdoc-openapi와 Swagger UI로 문서화한다.
- Controller 구현에 Swagger 애노테이션을 반복하지 않는다.
- 추가 설명이 필요한 API는 Controller 옆 `docs` 패키지의 `{Domain}ApiDocs` 문서 계약 인터페이스로 분리한다.
- 공통 메타데이터, 인증과 오류 응답은 `global.config`의 OpenAPI 설정과 `OpenApiCustomizer`에서 관리한다.
- DTO의 `@Schema`는 자동 추론으로 의미를 전달할 수 없는 필드에만 사용한다.
- API 변경 시 OpenAPI 통합 테스트와 `openApiDocs` 산출물을 함께 갱신한다.

## 경계

- 공개 `/api/v1/**`, DB, 외부 API와 결과 저장은 Spring API가 담당한다.
- FastAPI MCP는 private network로만 호출하고 사용자 JWT나 원천 외부 API 응답을 전달하지 않는다.
- FastAPI 서비스의 소스나 Python 의존성을 이 디렉터리에 섞지 않는다.
