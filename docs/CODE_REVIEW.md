# 코드리뷰

## PR 전 Reviewer 세션

Reviewer는 `develop...HEAD`를 기준으로 Issue와 Acceptance Criteria, TDD 증거, 테스트 실효성, 계층 경계, Validation·오류 응답, 인증·인가, 민감정보, 트랜잭션, 동시성, N+1, Docker와 품질 게이트를 확인합니다. 원칙적으로 운영 코드를 직접 고치지 않고 필요한 변경을 파일과 검증 방법까지 명시해 Developer에게 돌려보냅니다.

Finding은 BLOCKER, MAJOR, MINOR, NIT로 나눕니다. 필수 수정 MINOR를 포함해 수정사항이 하나라도 있거나 테스트·Docker·Acceptance Criteria가 미충족이면 `CHANGES_REQUESTED`입니다. 선택적 NIT만 있고 필수 수정사항이 0개일 때만 `APPROVED`입니다.

승인 시 `.codex/state/reviews/{브랜치}.json`에 Issue 번호, 브랜치, HEAD SHA, 판정, 리뷰 시각, 품질 게이트 SHA와 필수 수정 수를 기록합니다. 커밋이나 코드가 바뀌면 승인은 즉시 무효입니다.

## PR 생성 후 공식 리뷰

GitHub PR에서는 최소 1명 승인, stale approval 취소, 모든 대화 해결, CI 통과를 요구합니다. 내부 Reviewer 승인 파일은 공식 사람 리뷰를 대체하지 않습니다. 자동 머지는 사용하지 않습니다.
