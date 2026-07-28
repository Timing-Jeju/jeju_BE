#!/bin/sh
set -eu

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || {
  echo "Git 저장소에서 실행하세요." >&2
  exit 1
}
cd "$ROOT"

command -v git >/dev/null || { echo "Git이 필요합니다." >&2; exit 1; }
command -v python3 >/dev/null || { echo "Python 3가 필요합니다." >&2; exit 1; }
[ -x ./gradlew ] || { echo "실행 가능한 Gradle Wrapper가 필요합니다." >&2; exit 1; }

chmod +x .githooks/pre-commit .githooks/commit-msg .githooks/pre-push
chmod +x scripts/*.sh scripts/git-hooks/*.py 2>/dev/null || true
git config core.hooksPath .githooks

echo "Git Hook 설치 완료: $(git config --get core.hooksPath)"
echo "Hook 테스트: python3 -m unittest discover -s .codex/hooks/tests -p 'test_*.py'"
