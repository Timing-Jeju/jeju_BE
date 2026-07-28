---
name: pm-issue
description: 사용자 요구사항을 중복과 범위까지 검토해 구현 가능한 한국어 GitHub Issue로 작성한다. 새 기능, 버그, 빌드, 문서, 리팩터링, 테스트 또는 Release 작업을 Issue로 시작하거나 개발 세션에 넘길 명세가 필요할 때 사용한다.
---

# PM Issue 작성

AGENTS.md와 관련 프로젝트 문서를 읽고 원격 저장소·기존 Issue를 읽기 전용으로 확인한다. 운영 코드, 기능 브랜치, PR은 변경하지 않는다.

## 절차

1. 요구사항의 문제, 사용자 가치, 제약과 미결정을 분리한다.
2. 기존 Issue와 중복을 확인하고, 한 Issue가 독립적으로 검증하기 어렵다면 순서와 의존성을 가진 여러 Issue로 나눈다.
3. 작업 type을 결정하고 `[Feat]`, `[Fix]`, `[Build]`, `[Chore]`, `[Docs]`, `[Style]`, `[Refactor]`, `[Test]`, `[Release]` 중 제목 prefix를 선택한다.
4. 아래 항목을 한국어로 작성한다. 해당 없음은 이유를 적는다.
   - 배경과 문제, 목표, 사용자 요구사항
   - 범위와 제외 범위
   - 기능·비기능 요구사항
   - API와 Request/Response 예시
   - Validation, 오류 코드, DB/Schema, 보안·권한
   - 의존 Issue와 위험
   - Given/When/Then Acceptance Criteria
   - 성공·실패·경계값 TDD 시나리오
   - Docker·운영 확인과 Definition of Done
5. `REMOTE_SETUP_MODE=dry-run`에서는 원격 변경을 하지 않고 `gh issue create` 명령을 제시한다. `apply`일 때만 인증, remote, 저장소를 재확인한 뒤 사용자 범위 안에서 생성한다.

## 출력

```text
PM_RESULT: ISSUE_CREATED 또는 ISSUE_DRAFTED
ISSUE_TYPE:
ISSUE_NUMBER:
ISSUE_TITLE:
ISSUE_URL:
RECOMMENDED_BRANCH:
DEPENDENCIES:
RISKS:
DEVELOPER_HANDOFF:
```
