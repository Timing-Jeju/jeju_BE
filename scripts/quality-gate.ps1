param(
  [switch]$SetupValidation,
  [switch]$Ci,
  [ValidateSet("all", "common", "spring")]
  [string]$Scope = "all"
)
$ErrorActionPreference = "Stop"

function Write-Stage([string]$Message) {
  Write-Host "[품질 게이트] $Message"
}

function Invoke-Native([string]$Description, [scriptblock]$Command) {
  & $Command
  if ($LASTEXITCODE -ne 0) {
    throw "$Description 실패 (exit code: $LASTEXITCODE)"
  }
}

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
      Invoke-Native "보호 브랜치 검사" { py -3 scripts/git-hooks/validate-branch.py --allow-protected $branch }
    } else {
      Invoke-Native "브랜치 검사" { py -3 scripts/git-hooks/validate-branch.py $branch }
    }
    if (git status --porcelain) { throw "작업 트리가 깨끗하지 않습니다." }
  }

  Invoke-Native "비밀정보 검사" { py -3 scripts/git-hooks/scan-staged-secrets.py --all-files }
  Invoke-Native "REST 공통 계약 검사" { py -3 scripts/validate_rest_contracts.py }
  Invoke-Native "관광지 계약 검사" { py -3 scripts/validate_places_contract.py }
  Invoke-Native "관심 장소 계약 검사" { py -3 scripts/validate_saved_places_contract.py }
  Invoke-Native "여행 계약 검사" { py -3 scripts/validate_trips_contract.py }
  Invoke-Native "선호·교통 계약 검사" { py -3 scripts/validate_preferences_transport_contract.py }
  Invoke-Native "숙소 계약 검사" { py -3 scripts/validate_accommodations_contract.py }
  Invoke-Native "일정 계약 검사" { py -3 scripts/validate_schedules_contract.py }
  Write-Stage "날씨 예보 API 계약 검사"
  Invoke-Native "날씨 예보 계약 검사" { py -3 scripts/validate_weather_forecast_contract.py }

  Invoke-Native "가능성·이동 구간 계약 검사" { py -3 scripts/validate_feasibility_legs_contract.py }
  Write-Stage "위치정보 수집·보존·삭제 정책 계약 검사"
  Invoke-Native "위치정보 보존 정책 계약 검사" { py -3 scripts/validate_location_retention_contract.py }
  Invoke-Native "Codex hook 테스트" { py -3 -m unittest discover -s .codex/hooks/tests -p test_*.py }
  Invoke-Native "Git hook 테스트" { py -3 -m unittest discover -s scripts/git-hooks/tests -p test_*.py }
  Invoke-Native "저장소 자동화 테스트" { py -3 -m unittest discover -s scripts/tests -p test_*.py }
}

if ($Scope -in @("all", "spring")) {
  Push-Location $springDir
  try {
    ./gradlew.bat --no-daemon spotlessCheck
    ./gradlew.bat --no-daemon classes testClasses
    ./gradlew.bat --no-daemon unitTest sliceTest integrationTest architectureTest
    ./gradlew.bat --no-daemon openApiDocs
    ./gradlew.bat --no-daemon test jacocoTestReport jacocoTestCoverageVerification bootJar
  } finally {
    Pop-Location
  }
  ./scripts/docker-smoke-test.ps1
}

if ($Scope -eq "all" -and -not $SetupValidation -and -not $Ci -and $sha -ne "UNBORN") {
  $safeBranch = $branch -replace '[^A-Za-z0-9._-]', '__'
  $stateDir = ".codex/state/quality-gates"
  New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
  $payload = [ordered]@{
    branch = $branch; headSha = $sha; checkedAt = [DateTime]::UtcNow.ToString("o")
    gradleCheck = "SUCCESS"; architectureTest = "SUCCESS"; coverageCheck = "SUCCESS"; openApiDocs = "SUCCESS"
    dockerBuild = "SUCCESS"; dockerSmokeTest = "SUCCESS"; result = "SUCCESS"
  }
  $payload | ConvertTo-Json | Set-Content -Encoding utf8 "$stateDir/$safeBranch.json"
}
Write-Host "[품질 게이트] 모든 단계 성공"
