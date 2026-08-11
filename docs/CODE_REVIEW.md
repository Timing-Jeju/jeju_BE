# 코드리뷰

## PR 전 Reviewer 세션

실제 `timing-jeju-reviewer`는 Developer·PM과 분리된 독립 세션에서 `develop...HEAD`를 기준으로 Issue와 Acceptance Criteria, TDD 증거, 테스트 실효성, 계층 경계, Validation·오류 응답, 인증·인가, 민감정보, 트랜잭션, 동시성, N+1, Docker와 품질 게이트를 확인합니다. 원칙적으로 운영 코드를 직접 고치지 않고 필요한 변경을 파일과 검증 방법까지 명시해 Developer에게 돌려보냅니다.

Finding은 BLOCKER, MAJOR, MINOR, NIT로 나눕니다. 등급이나 필수·선택 여부와 관계없이 finding이 하나라도 있거나 테스트·Docker·Acceptance Criteria가 미충족이면 `CHANGES_REQUESTED`입니다. 모든 검증이 충족되고 finding 0건일 때만 `APPROVED`입니다.

Developer와 PM은 승인 상태 파일을 생성·수정·삭제할 수 없습니다. `scripts/record_review_state.py`도 실행할 수 없습니다. 실제 timing-jeju-reviewer만 독립 검토에서 finding 0건으로 판정한 APPROVED 직후 다음 정규 명령을 추가 사용자 확인 없이 실행합니다.

```bash
python3 scripts/record_review_state.py --issue <issue> --verdict APPROVED --findings-count 0 --required-changes-count 0
```

명령은 현재 작업 브랜치와 Issue, 깨끗한 작업 트리, 로컬·원격 HEAD, 동일 SHA 품질 게이트 SUCCESS를 다시 확인한 뒤 `.codex/state/reviews/{sanitized-branch}.json`에 `issueNumber`, `branch`, `headSha`, `verdict`, `reviewedAt`, `qualityGateSha`, `requiredChangesCount`를 원자적으로 기록합니다. 같은 HEAD의 유효 승인은 멱등으로 유지합니다. `headSha`와 `qualityGateSha`는 현재 HEAD와 같고 `requiredChangesCount`는 0이어야 합니다. 커밋이나 코드가 바뀌면 승인은 즉시 stale 상태가 되어 무효입니다.

`CHANGES_REQUESTED`이면 실제 `timing-jeju-reviewer`만 실제 finding 수를 두 count에 입력해 같은 명령을 실행하고 현재 브랜치의 기존 stale 승인 상태 파일만 제거합니다. 다른 브랜치 상태는 건드리지 않습니다. 승인 상태 파일은 로컬 ignored 산출물이며 커밋하지 않습니다. 승인 JSON의 직접 편집과 임의 경로·`--force` 우회는 금지합니다.

프로세스가 호출자 역할을 OS 수준에서 증명할 수는 없습니다. 이 기술적 한계를 Reviewer 전용 agent·스킬 계약, 독립 검토 증거와 직접 파일 조작 차단 Hook으로 보완합니다. 기록 명령은 Reviewer의 판단을 만들거나 대체하지 않으며, 실제 독립 검토 없이 실행하거나 역할을 가장하는 것은 승인 게이트 우회입니다.

## PR 생성 후 공식 리뷰

GitHub PR에서는 최소 1명 승인, stale approval 취소, 모든 대화 해결, CI 통과를 요구합니다. 내부 Reviewer 승인 파일은 공식 사람 리뷰를 대체하지 않습니다. 자동 머지는 사용하지 않습니다.
