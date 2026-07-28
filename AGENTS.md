# Timing Jeju API 에이전트 규칙

## 프로젝트와 명령

- Java 21, Spring Boot 4.1, Gradle Wrapper 기반 모놀리식 API다.
- 코드는 `com.timingjeju.api.domain` 아래에 도메인별로 모으고, 도메인 안에서 MVC 계층을 나눈다. 공통 관심사만 `global`에 둔다.
- 로컬 실행: `./gradlew bootRun`
- 전체 검사: `./gradlew clean check`
- 필수 품질 게이트: `./scripts/quality-gate.sh`
- Docker 검증: `./scripts/docker-smoke-test.sh`

## 필수 개발 흐름

- 모든 변경은 GitHub Issue에서 시작한다. `main`과 `develop`에서 직접 개발·커밋·푸시하지 않는다.
- 최신 `develop`에서 `{type}/{issue-number}-{kebab-case-summary}` 브랜치를 만든다.
- 운영 코드보다 테스트를 먼저 작성하고 Red → Green → Refactor 증거를 남긴다.
- Developer는 PR을 만들지 않는다. Reviewer가 최신 HEAD를 승인한 뒤 `$create-pr`만 사용한다.
- PM은 Issue만, Developer는 TDD 구현만, Reviewer는 `develop...HEAD` 검토만 담당한다.
- 매 개발일 종료 전에 한국어 개발 일지를 Obsidian `04_Projects/timing-jeju`에 남긴다.

## 완료 조건과 금지사항

- 테스트, ArchUnit, 커버리지, 빌드, Docker 실행·헬스 체크와 정리가 모두 성공해야 한다.
- `.env`, 키, 토큰, 인증서 등 비밀정보를 커밋하거나 로그에 출력하지 않는다.
- Hook 우회, force push, 파괴적 Git 명령, 자동 머지, 승인 파일 조작을 금지한다.
- 세부 규칙은 [아키텍처](docs/ARCHITECTURE.md), [Git 흐름](docs/GIT_WORKFLOW.md), [TDD](docs/TDD_GUIDE.md), [코드리뷰](docs/CODE_REVIEW.md), [완료 정의](docs/DEFINITION_OF_DONE.md)를 따른다.
