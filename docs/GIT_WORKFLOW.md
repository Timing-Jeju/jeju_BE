# Git 및 출시 흐름

## 브랜치

- `main`: 즉시 출시 가능한 코드만 유지합니다. Release PR만 받습니다.
- `develop`: 다음 출시의 통합 브랜치입니다. 전체 품질 게이트를 통과한 PR만 받습니다.
- 작업 브랜치: `{type}/{issue-number}-{영문-kebab-case-summary}`

```bash
git fetch origin
git switch develop
git pull --ff-only origin develop
git switch -c feat/14-place-search
```

커밋 형식은 `type: #이슈번호 요약`입니다. 작업 브랜치 번호와 커밋 번호가 일치해야 합니다.

## 기능 개발

PM 세션 → Issue 생성 → Developer 세션 → 최신 develop → 작업 브랜치 → TDD → 작업 단위 커밋 → 로컬 품질 게이트 → PR 전 Reviewer → 승인 → PR 생성 → GitHub CI와 공식 리뷰 → develop 머지 순서입니다.

PR 전 리뷰는 독립 Reviewer가 수행하는 내부 품질 게이트입니다. Reviewer는 판정 완료 후 저장소의 `scripts/record_review_state.py`로만 결과를 기록하며, PM·Developer와 create-pr 단계는 승인 상태를 만들거나 수정하지 않습니다. PR 생성 후 리뷰는 GitHub에서 사람의 승인과 CI를 받는 공식 절차입니다. 두 절차를 서로 대체하지 않습니다.

## 출시

Release Issue가 있고 `develop` 전체 검증이 완료된 경우에만 `[Release] #번호 요약` PR을 `develop → main`으로 만듭니다. 별도 release 브랜치가 꼭 필요하면 `develop`에서 `release/{issue}-{summary}`로 만들고 최종 반영 경로를 Release Issue에 기록합니다.
