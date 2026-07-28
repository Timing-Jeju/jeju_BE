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

## CI

`.github/workflows/ci.yml`은 `develop`·`main` 대상 Pull Request와 두 브랜치의 push에서 실행됩니다. 저장소 읽기 권한만 사용하며 다음 항목을 검증합니다.

- PR 제목, Issue 연결과 Release 브랜치 경로
- 변경 경로에 따른 Spring·서비스 계약 실행 범위
- Java 21과 Gradle Wrapper 무결성
- 저장소 정책, Spring 테스트, Architecture와 JaCoCo 커버리지
- Spring OpenAPI JSON 생성과 CI Artifact 보존
- Docker 이미지, Compose 실행, Actuator Health Check와 리소스 정리

공통 검사는 항상 실행합니다. Spring 구현이 바뀌면 Spring 검사를 추가하고, 서비스 계약이 바뀌면 Spring과 계약 검사를 수행합니다. FastAPI의 Python 검사는 별도 [jeju_AI 저장소](https://github.com/Timing-Jeju/jeju_AI) CI가 담당합니다. `quality-gate` Job은 실행 대상 Job의 성공을 하나의 필수 체크로 집계합니다.

같은 브랜치에서 새 실행이 시작되면 이전 실행을 취소합니다. Spring 테스트 결과는 실패 여부와 관계없이 Artifact로 14일간 보존하며, CI 계약 자체는 `scripts/tests/test_ci_workflow.py`로 회귀 검증합니다.
