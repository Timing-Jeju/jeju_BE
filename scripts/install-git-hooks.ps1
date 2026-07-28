$ErrorActionPreference = "Stop"

$root = git rev-parse --show-toplevel
if (-not $root) { throw "Git 저장소에서 실행하세요." }
Set-Location $root

if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw "Git이 필요합니다." }
if (-not (Get-Command py -ErrorAction SilentlyContinue)) { throw "Python 3 Launcher(py)가 필요합니다." }
if (-not (Test-Path "./gradlew.bat")) { throw "Gradle Wrapper가 필요합니다." }

git config core.hooksPath .githooks
Write-Host "Git Hook 설치 완료: $(git config --get core.hooksPath)"
Write-Host "Hook 테스트: py -3 -m unittest discover -s .codex/hooks/tests -p test_*.py"
