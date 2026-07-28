param(
  [switch]$SetupValidation,
  [switch]$Ci,
  [ValidateSet("all", "common", "spring", "fastapi")]
  [string]$Scope = "all"
)
$ErrorActionPreference = "Stop"

$root = git rev-parse --show-toplevel
Set-Location $root
$springDir = Join-Path $root "services/spring-api"
$branch = if ($env:GITHUB_HEAD_REF) { $env:GITHUB_HEAD_REF } else { git branch --show-current }
$sha = git rev-parse HEAD 2>$null
if (-not $sha) { $sha = "UNBORN" }

if ($Scope -in @("all", "common")) {
  Write-Host "[품질 게이트] Git 상태와 브랜치 검사"
  git status --short
  if (-not $SetupValidation) {
    if ($Ci -and $branch -in @("main", "develop")) {
      py -3 scripts/git-hooks/validate-branch.py --allow-protected $branch
    } else {
      py -3 scripts/git-hooks/validate-branch.py $branch
    }
    if (git status --porcelain) { throw "작업 트리가 깨끗하지 않습니다." }
  }

  py -3 scripts/git-hooks/scan-staged-secrets.py --all-files
  py -3 -m unittest discover -s .codex/hooks/tests -p test_*.py
  py -3 -m unittest discover -s scripts/git-hooks/tests -p test_*.py
  py -3 -m unittest discover -s scripts/tests -p test_*.py
}

if ($Scope -in @("all", "spring")) {
  Push-Location $springDir
  try {
    ./gradlew.bat --no-daemon spotlessCheck
    ./gradlew.bat --no-daemon classes testClasses
    ./gradlew.bat --no-daemon unitTest sliceTest integrationTest architectureTest
    ./gradlew.bat --no-daemon test jacocoTestReport jacocoTestCoverageVerification bootJar
  } finally {
    Pop-Location
  }
  ./scripts/docker-smoke-test.ps1
}

if ($Scope -in @("all", "fastapi")) {
  ./services/fastapi-mcp/scripts/quality-gate.ps1
}

if ($Scope -eq "all" -and -not $SetupValidation -and -not $Ci -and $sha -ne "UNBORN") {
  $safeBranch = $branch -replace '[^A-Za-z0-9._-]', '__'
  $stateDir = ".codex/state/quality-gates"
  New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
  $payload = [ordered]@{
    branch = $branch; headSha = $sha; checkedAt = [DateTime]::UtcNow.ToString("o")
    gradleCheck = "SUCCESS"; architectureTest = "SUCCESS"; coverageCheck = "SUCCESS"; fastapiCheck = "SUCCESS"
    dockerBuild = "SUCCESS"; dockerSmokeTest = "SUCCESS"; result = "SUCCESS"
  }
  $payload | ConvertTo-Json | Set-Content -Encoding utf8 "$stateDir/$safeBranch.json"
}
Write-Host "[품질 게이트] 모든 단계 성공"
