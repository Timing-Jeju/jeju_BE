param(
  [switch]$SetupValidation,
  [switch]$Ci
)
$ErrorActionPreference = "Stop"

$root = git rev-parse --show-toplevel
Set-Location $root
$branch = if ($env:GITHUB_HEAD_REF) { $env:GITHUB_HEAD_REF } else { git branch --show-current }
$sha = git rev-parse HEAD 2>$null
if (-not $sha) { $sha = "UNBORN" }

Write-Host "[품질 게이트] Git 상태와 브랜치 검사"
git status --short
if (-not $SetupValidation) {
  py -3 scripts/git-hooks/validate-branch.py $branch
  if (git status --porcelain) { throw "작업 트리가 깨끗하지 않습니다." }
}

py -3 scripts/git-hooks/scan-staged-secrets.py --all-files
./gradlew.bat --no-daemon spotlessCheck
./gradlew.bat --no-daemon classes testClasses
./gradlew.bat --no-daemon unitTest sliceTest integrationTest architectureTest
./gradlew.bat --no-daemon test jacocoTestReport jacocoTestCoverageVerification bootJar
./scripts/docker-smoke-test.ps1

if (-not $SetupValidation -and -not $Ci -and $sha -ne "UNBORN") {
  $safeBranch = $branch -replace '[^A-Za-z0-9._-]', '__'
  $stateDir = ".codex/state/quality-gates"
  New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
  $payload = [ordered]@{
    branch = $branch; headSha = $sha; checkedAt = [DateTime]::UtcNow.ToString("o")
    gradleCheck = "SUCCESS"; architectureTest = "SUCCESS"; coverageCheck = "SUCCESS"
    dockerBuild = "SUCCESS"; dockerSmokeTest = "SUCCESS"; result = "SUCCESS"
  }
  $payload | ConvertTo-Json | Set-Content -Encoding utf8 "$stateDir/$safeBranch.json"
}
Write-Host "[품질 게이트] 모든 단계 성공"
