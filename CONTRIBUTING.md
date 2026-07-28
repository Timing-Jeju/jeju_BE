# 기여 가이드

## Issue 우선 원칙

모든 변경은 중복 여부를 확인한 GitHub Issue에서 시작합니다. Issue에는 범위, 제외 범위, Acceptance Criteria, 테스트 시나리오와 완료 조건을 적습니다. 원격 설정 모드가 `dry-run`이면 `$pm-issue`가 완성된 초안과 실행할 `gh` 명령만 제공합니다.

## 브랜치와 커밋

최신 `develop`에서 `{type}/{issue-number}-{영문-kebab-case}` 형식으로 브랜치를 만듭니다. 허용 type은 `feat`, `fix`, `build`, `chore`, `docs`, `style`, `refactor`, `test`, `release`입니다.

```text
feat/14-place-search
feat: #14 관광지 검색 API 구현
```

`main`과 `develop`에는 직접 커밋·푸시하지 않습니다. Merge commit과 Git이 만든 `Revert ...` 커밋만 메시지 형식 예외이며, 일반 커밋은 브랜치의 Issue 번호와 일치해야 합니다.

## TDD와 리뷰

Acceptance Criteria를 성공·실패·경계값 테스트로 바꾸고 Red 실패를 실제 확인한 다음 최소 구현으로 Green을 만듭니다. 리팩터링 후 전체 테스트를 다시 실행하고 명령과 결과를 기록합니다.

Developer 세션은 품질 게이트와 Docker 검증 뒤 PR을 만들지 않고 Reviewer에게 넘깁니다. Reviewer는 `develop...HEAD`를 검토하며 필수 수정사항이 하나라도 있으면 개발 세션으로 반환합니다. 최신 HEAD가 APPROVED이고 품질 게이트 기록도 최신일 때만 `scripts/create-pr.*`로 PR을 생성합니다.

## 제출 전 확인

```bash
./scripts/quality-gate.sh
```

완료 기준은 [Definition of Done](docs/DEFINITION_OF_DONE.md)을 따릅니다. 자동 머지와 Hook 우회는 허용하지 않습니다.
