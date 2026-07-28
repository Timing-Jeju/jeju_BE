---
name: pre-pr-review
description: develop...HEAD 변경을 Issue, TDD, 테스트, 보안, 아키텍처, Docker와 품질 게이트 관점에서 검토하고 PR 전 APPROVED 또는 CHANGES_REQUESTED를 판정한다. 개발 완료 후 PR 생성 전에 독립 리뷰가 필요할 때 사용한다.
---

# PR 전 코드리뷰

AGENTS.md, Issue와 `docs/CODE_REVIEW.md`를 읽는다. 운영 코드는 직접 수정하지 않고 구체적 finding과 검증 방법을 Developer에게 반환한다.

## 절차

1. 브랜치, HEAD SHA, Issue 번호와 `develop...HEAD` 범위를 고정한다.
2. 요구사항·Acceptance Criteria·Red/Green/Refactor 증거와 테스트 변경을 대조한다.
3. Controller/Service/Repository 경계, Entity 직접 응답, DTO, Validation, 예외·오류 응답, 트랜잭션과 도메인 순환을 검토한다.
4. 인증·인가, 입력값, SQL Injection, 비밀정보·로그, N+1, 쿼리, null, 동시성, 환경변수와 회귀 위험을 검토한다.
5. 테스트, ArchUnit, 커버리지, 빌드, Docker smoke와 최신 품질 게이트를 직접 확인한다.
6. finding을 BLOCKER, MAJOR, MINOR, NIT로 분류한다. 수정이 필요한 항목이 하나라도 있으면 `CHANGES_REQUESTED`다.
7. `APPROVED`이면 현재 HEAD 기준 `.codex/state/reviews/{sanitized-branch}.json`을 생성한다. `CHANGES_REQUESTED`이면 기존 승인 파일을 제거한다. 승인 파일은 커밋하지 않는다.

승인 JSON에는 `issueNumber`, `branch`, `headSha`, `verdict`, `reviewedAt`, `qualityGateSha`, `requiredChangesCount`를 기록한다. 실제 검토 없이 만들지 않는다.

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
