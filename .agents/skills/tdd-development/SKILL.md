---
name: tdd-development
description: GitHub Issue 번호를 받아 최신 develop 기반 작업 브랜치에서 Red-Green-Refactor로 구현하고 테스트·Docker·품질 게이트 증거를 남긴다. 기능, 수정, 리팩터링 등 운영 코드 변경을 개발하거나 Reviewer에게 넘길 결과를 만들 때 사용한다.
---

# TDD 개발

Issue 번호 없이는 시작하지 않는다. AGENTS.md, Issue, 아키텍처·TDD·완료 정의 문서를 먼저 읽는다.

## 절차

1. `gh issue view`로 요구사항과 Acceptance Criteria를 읽어 요약한다.
2. 작업 트리가 깨끗한지 확인하고 최신 `develop`을 fast-forward로 받은 뒤 규칙에 맞는 Issue 브랜치를 만든다. 상태가 안전하지 않으면 멈추고 정확한 원인을 보고한다.
3. 구현 계획보다 성공·실패·경계값 테스트 목록을 먼저 작성한다.
4. 운영 코드보다 테스트를 먼저 추가하고 관련 테스트를 실행해 의도한 이유로 실패하는 Red를 확인한다. 명령, 테스트명, 핵심 실패를 기록한다.
5. 최소 구현으로 Green을 만들고 관련 테스트와 전체 테스트를 실행한다.
6. 중복, 이름, 책임, 트랜잭션, 도메인·MVC 경계를 Refactor하고 전체 테스트를 재실행한다.
7. 하나의 논리 변경 단위로 커밋한다. Hook을 우회하지 않는다.
8. `./scripts/quality-gate.sh`와 Docker 검증을 실행한다.
9. PR을 만들지 않고 Reviewer 세션에 넘긴다.

품질 게이트나 Docker 결과가 없거나 실패하면 `READY_FOR_REVIEW`를 선언하지 않는다.

## 출력

```text
DEVELOPMENT_RESULT: READY_FOR_REVIEW 또는 BLOCKED
ISSUE_NUMBER:
BRANCH:
COMMITS:
CHANGED_FILES:
RED_EVIDENCE:
GREEN_EVIDENCE:
REFACTOR_SUMMARY:
TEST_COMMANDS:
TEST_RESULTS:
DOCKER_RESULT:
KNOWN_RISKS:
REVIEWER_HANDOFF:
```
