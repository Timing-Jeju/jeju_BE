---
name: pre-pr-review
description: develop...HEAD 변경을 Issue, TDD, 테스트, 보안, 아키텍처, Docker와 품질 게이트 관점에서 검토하고 PR 전 APPROVED 또는 CHANGES_REQUESTED를 판정한다. 개발 완료 후 PR 생성 전에 독립 리뷰가 필요할 때 사용한다.
---

# PR 전 코드리뷰

AGENTS.md, Issue와 `docs/CODE_REVIEW.md`를 읽는다. 이 스킬은 Developer·PM과 분리된 실제 timing-jeju-reviewer 세션에서만 승인 상태 파일을 다룰 수 있다. 운영 코드는 직접 수정하지 않고 구체적 finding과 검증 방법을 Developer에게 반환한다.

## 절차

1. 브랜치, HEAD SHA, Issue 번호와 `develop...HEAD` 범위를 고정한다.
2. 요구사항·Acceptance Criteria·Red/Green/Refactor 증거와 테스트 변경을 대조한다.
3. Controller/Service/Repository 경계, Entity 직접 응답, DTO, Validation, 예외·오류 응답, 트랜잭션과 도메인 순환을 검토한다.
4. 인증·인가, 입력값, SQL Injection, 비밀정보·로그, N+1, 쿼리, null, 동시성, 환경변수와 회귀 위험을 검토한다.
5. 테스트, ArchUnit, 커버리지, 빌드, Docker smoke와 최신 품질 게이트를 직접 확인한다.
6. finding을 BLOCKER, MAJOR, MINOR, NIT로 분류한다. 필수·선택 여부와 관계없이 finding이 하나라도 있으면 `CHANGES_REQUESTED`이며 finding이 0건일 때만 `APPROVED`할 수 있다.
7. 실제 timing-jeju-reviewer가 독립 `develop...HEAD` 검토를 finding이 0건으로 완료하고 모든 게이트를 확인한 경우에만 APPROVED 직후 현재 HEAD 기준 `.codex/state/reviews/{sanitized-branch}.json`을 생성한다.
8. CHANGES_REQUESTED이면 기존 stale 승인 상태 파일만 제거한다. Developer·PM은 이 파일을 생성·수정·삭제할 수 없고, 승인 상태 파일은 커밋하지 않는다.

승인 JSON에는 `issueNumber`, `branch`, `headSha`, `verdict`, `reviewedAt`, `qualityGateSha`, `requiredChangesCount`를 기록한다. `headSha`와 `qualityGateSha`는 현재 HEAD와 같고 `verdict`는 `APPROVED`, `requiredChangesCount`는 0이어야 한다. 기존 sanitized branch 경로와 schema를 바꾸지 않는다. 실제 검토 없이 만들거나 Reviewer 역할을 가장해 만들지 않는다.

## 출력

```text
REVIEW_RESULT: APPROVED 또는 CHANGES_REQUESTED
ISSUE_NUMBER:
BRANCH:
HEAD_SHA:
SUMMARY:
FINDINGS:
- SEVERITY:
  FILE:
  LINE:
  PROBLEM:
  REASON:
  REQUIRED_CHANGE:
  VERIFICATION:
TEST_RESULTS:
DOCKER_RESULT:
SECURITY_RESULT:
ARCHITECTURE_RESULT:
DEVELOPER_HANDOFF:
PR_HANDOFF:
```
