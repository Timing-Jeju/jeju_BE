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
- Developer와 PM은 승인 상태 파일을 생성·수정·삭제할 수 없다. `scripts/record_review_state.py`도 실행할 수 없다. 승인 상태 파일은 `.codex/state/reviews/{sanitized-branch}.json`이다.
- 실제 timing-jeju-reviewer만 독립적인 `develop...HEAD` 검토를 완료한 뒤 `scripts/record_review_state.py`를 실행한다. finding 0건으로 APPROVED하면 현재 HEAD의 승인 상태를 기록하고, CHANGES_REQUESTED이면 현재 브랜치의 기존 stale 승인 상태만 삭제한다. 승인 JSON을 `apply_patch`·에디터·임의 셸 명령으로 직접 조작하지 않는다.
- Reviewer 전용 기록 명령은 브랜치·Issue·로컬/원격 HEAD·동일 SHA 품질 게이트·finding 수를 다시 검증하는 저장소 정규 절차다. Reviewer의 판단을 대신하지 않으며 검토 완료 직후 추가 사용자 확인 없이 실행한다.
- Hook은 실수와 통상적인 직접 조작을 줄이는 방어층이며 동일 OS 사용자의 임의 인코딩 셸을 완전히 차단하는 OS 보안 경계가 아니다. 승인 신뢰 경계는 독립 Reviewer의 `develop...HEAD` 판정, 공식 recorder의 저장소 상태 검증, create-pr의 동일 HEAD·품질 게이트·승인 상태 재검증을 합친 절차다.
- 매 개발일 종료 전에 한국어 개발 일지를 Obsidian `04_Projects/timing-jeju`에 남긴다.

## 완료 조건과 금지사항

- 테스트, ArchUnit, 커버리지, 빌드, Docker 실행·헬스 체크와 정리가 모두 성공해야 한다.
- `.env`, 키, 토큰, 인증서 등 비밀정보를 커밋하거나 로그에 출력하지 않는다.
- Hook 우회, force push, 파괴적 Git 명령과 자동 머지를 금지한다. 승인 상태 파일 직접 조작도 금지하며, 위에서 명시한 실제 `timing-jeju-reviewer`의 검증된 기록 명령 실행만 예외다.
- 세부 규칙은 [아키텍처](docs/ARCHITECTURE.md), [Git 흐름](docs/GIT_WORKFLOW.md), [TDD](docs/TDD_GUIDE.md), [코드리뷰](docs/CODE_REVIEW.md), [완료 정의](docs/DEFINITION_OF_DONE.md)를 따른다.
