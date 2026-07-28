# GitHub 저장소 설정

현재 원격 설정 모드는 `dry-run`입니다. 원격 저장소와 인증이 확정되기 전에는 Label, Ruleset, Issue, PR을 생성하지 않습니다.

## Label

```bash
REMOTE_SETUP_MODE=dry-run ./scripts/github/setup-labels.sh
REMOTE_SETUP_MODE=apply ./scripts/github/setup-labels.sh owner/repository
```

스크립트는 같은 이름의 Label을 `--force`로 갱신하므로 반복 실행할 수 있습니다. 기준 목록은 `.github/labels.yml`입니다.

## Ruleset

```bash
REMOTE_SETUP_MODE=dry-run ./scripts/github/setup-ruleset.sh
REMOTE_SETUP_MODE=apply ./scripts/github/setup-ruleset.sh owner/repository
```

`main`과 `develop`에는 직접 push·삭제·force push 금지, PR 필수, 승인 1명, 새 push 시 stale 승인 취소, 마지막 push 승인, 대화 해결, `quality-gate` 통과를 요구합니다. GitHub Ruleset만으로 PR head가 `develop`인지 완전히 제한하기 어려운 부분은 CI `pr-metadata` 검증에서 `[Release]` 제목과 함께 강제합니다. 자동 머지는 비활성화합니다.

적용 전에는 `gh auth status`, `gh repo view`, 대상 owner/repository와 기본 브랜치를 다시 확인합니다. 스크립트가 적용한 뒤에도 GitHub Settings 화면에서 Ruleset 대상과 필수 Check 이름을 검토합니다.
