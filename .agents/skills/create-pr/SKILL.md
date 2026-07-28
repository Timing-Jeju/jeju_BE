---
name: create-pr
description: 최신 HEAD의 품질 게이트와 Reviewer 승인을 포함한 모든 PR 생성 전 조건을 확인한 뒤 저장소 제공 스크립트로 GitHub PR을 만든다. PR 전 리뷰에서 APPROVED를 받은 작업 또는 Release 브랜치의 PR을 생성할 때 사용한다.
---

# PR 생성

AGENTS.md와 `docs/GIT_WORKFLOW.md`를 읽고 raw `gh pr create` 대신 저장소 스크립트만 사용한다.

## 절차

1. `main`/`develop`이 아닌 규칙 브랜치인지, Issue 번호가 포함됐는지 확인한다.
2. 작업 트리가 깨끗하고 모든 변경이 커밋됐으며 원격 브랜치가 존재하는지 확인한다.
3. 품질 게이트와 Reviewer JSON이 현재 HEAD와 일치하고 필수 수정 수가 0인지 확인한다.
4. 일반 작업은 `develop`, Release만 `main`을 base로 선택한다.
5. macOS/Linux는 `./scripts/create-pr.sh --base <base>`, Windows는 `./scripts/create-pr.ps1 -Base <base>`를 실행한다.
6. PR 생성 뒤 자동 머지를 켜지 않는다.

하나라도 충족하지 않으면 PR을 만들지 않고 구체적 복구 행동을 반환한다.

## 출력

```text
PR_RESULT: CREATED 또는 BLOCKED
PR_NUMBER:
PR_URL:
BASE_BRANCH:
HEAD_BRANCH:
ISSUE_NUMBER:
QUALITY_GATE:
REVIEW_GATE:
BLOCK_REASON:
```
