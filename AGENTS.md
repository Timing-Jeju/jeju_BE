# Timing Jeju Spring 백엔드 에이전트 규칙

## 프로젝트와 명령

- 계약·DB·fixture·협업 정책은 루트에, Spring Boot 공개 API는 `services/spring-api`에 둔다.
- FastAPI MCP 구현은 별도 [Timing-Jeju/jeju_AI](https://github.com/Timing-Jeju/jeju_AI) 저장소에서 관리하며 Python 소스와 의존성을 이 저장소에 두지 않는다.
- Spring 로컬 실행: `cd services/spring-api && ./gradlew bootRun`
- Spring 전체 검사: `cd services/spring-api && ./gradlew clean check`
- 필수 품질 게이트: `./scripts/quality-gate.sh`
- Docker 검증: `./scripts/docker-smoke-test.sh`
- Spring 세부 규칙은 `services/spring-api/AGENTS.md`를 따른다.

## 필수 개발 흐름

- 모든 변경은 GitHub Issue에서 시작한다. `main`과 `develop`에서 직접 개발·커밋·푸시하지 않는다.
- 최신 `develop`에서 `{type}/{issue-number}-{kebab-case-summary}` 브랜치를 만든다.
- 운영 코드보다 테스트를 먼저 작성하고 Red → Green → Refactor 증거를 남긴다.
- Developer는 PR을 만들지 않는다. Reviewer가 최신 HEAD를 승인한 뒤 `$create-pr`만 사용한다.
- PM은 Issue만, Developer는 TDD 구현만, Reviewer는 `develop...HEAD` 검토만 담당한다.
- Developer와 PM은 승인 상태 파일을 생성·수정·삭제할 수 없다. 승인 상태 파일은 `.codex/state/reviews/{sanitized-branch}.json`이다.
- 실제 timing-jeju-reviewer만 독립적인 `develop...HEAD` 검토를 완료하고 finding 0건으로 APPROVED한 직후 현재 HEAD의 승인 상태 파일을 생성할 수 있다. CHANGES_REQUESTED이면 같은 Reviewer만 기존 stale 승인 상태 파일을 삭제할 수 있다.
- 매 개발일 종료 전에 한국어 개발 일지를 Obsidian `04_Projects/timing-jeju`에 남긴다.

## 완료 조건과 금지사항

- 테스트, ArchUnit, 커버리지, 빌드, Docker 실행·헬스 체크와 정리가 모두 성공해야 한다.
- `.env`, 키, 토큰, 인증서 등 비밀정보를 커밋하거나 로그에 출력하지 않는다.
- Hook 우회, force push, 파괴적 Git 명령과 자동 머지를 금지한다. 승인 상태 파일 조작도 금지하며, 위에서 명시한 실제 `timing-jeju-reviewer`의 `APPROVED` 생성 및 `CHANGES_REQUESTED` stale 파일 삭제만 예외다.
- 세부 규칙은 [아키텍처](docs/ARCHITECTURE.md), [Git 흐름](docs/GIT_WORKFLOW.md), [TDD](docs/TDD_GUIDE.md), [코드리뷰](docs/CODE_REVIEW.md), [완료 정의](docs/DEFINITION_OF_DONE.md)를 따른다.
