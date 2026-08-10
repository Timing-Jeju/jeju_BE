# 코드리뷰

## PR 전 Reviewer 세션

실제 `timing-jeju-reviewer`는 Developer·PM과 분리된 독립 세션에서 `develop...HEAD`를 기준으로 Issue와 Acceptance Criteria, TDD 증거, 테스트 실효성, 계층 경계, Validation·오류 응답, 인증·인가, 민감정보, 트랜잭션, 동시성, N+1, Docker와 품질 게이트를 확인합니다. 원칙적으로 운영 코드를 직접 고치지 않고 필요한 변경을 파일과 검증 방법까지 명시해 Developer에게 돌려보냅니다.

Finding은 BLOCKER, MAJOR, MINOR, NIT로 나눕니다. 등급이나 필수·선택 여부와 관계없이 finding이 하나라도 있거나 테스트·Docker·Acceptance Criteria가 미충족이면 `CHANGES_REQUESTED`입니다. 모든 검증이 충족되고 finding 0건일 때만 `APPROVED`입니다.

Developer와 PM은 승인 상태 파일을 생성·수정·삭제할 수 없습니다. 실제 timing-jeju-reviewer만 독립 검토에서 finding 0건으로 판정한 APPROVED 직후 `.codex/state/reviews/{sanitized-branch}.json`에 `issueNumber`, `branch`, `headSha`, `verdict`, `reviewedAt`, `qualityGateSha`, `requiredChangesCount`를 기록할 수 있습니다. 이때 `headSha`와 `qualityGateSha`는 현재 HEAD와 같고 `requiredChangesCount`는 0이어야 합니다. 커밋이나 코드가 바뀌면 승인은 즉시 stale 상태가 되어 무효입니다.

`CHANGES_REQUESTED`이면 실제 `timing-jeju-reviewer`만 해당 브랜치의 기존 stale 승인 상태 파일을 제거합니다. 승인 상태 파일은 로컬 ignored 산출물이며 커밋하지 않습니다. 실제 독립 검토 없이 생성하거나 역할을 가장해 수정하는 것은 승인 게이트 우회입니다.

## PR 생성 후 공식 리뷰

GitHub PR에서는 최소 1명 승인, stale approval 취소, 모든 대화 해결, CI 통과를 요구합니다. 내부 Reviewer 승인 파일은 공식 사람 리뷰를 대체하지 않습니다. 자동 머지는 사용하지 않습니다.
