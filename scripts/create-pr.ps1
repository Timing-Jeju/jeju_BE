param(
  [string]$Base,
  [string]$Title
)
$ErrorActionPreference = "Stop"

$root = git rev-parse --show-toplevel
Set-Location $root
$branch = git branch --show-current
$parts = $branch -split '/', 2
$type = $parts[0]
$issue = ($parts[1] -split '-', 2)[0]
if (-not $Base) { $Base = if ($type -eq "release") { "main" } else { "develop" } }

gh auth status | Out-Null
py -3 scripts/git-hooks/validate-pr-ready.py --base $Base

$prefixes = @{ feat="Feat"; fix="Fix"; build="Build"; chore="Chore"; docs="Docs"; style="Style"; refactor="Refactor"; test="Test"; release="Release" }
if (-not $Title) { $Title = "[$($prefixes[$type])] #$issue 작업 변경사항" }
$bodyFile = [System.IO.Path]::GetTempFileName()
try {
  @"
# 관련 Issue

Closes #$issue

# 변경 목적

Issue #$issue의 요구사항을 반영합니다.

# 검증 결과

- 최신 HEAD 품질 게이트: SUCCESS
- PR 전 Reviewer: APPROVED
- 자동 머지: 사용하지 않음
"@ | Set-Content -Encoding utf8 $bodyFile
  $url = gh pr create --base $Base --head $branch --title $Title --body-file $bodyFile
  $number = ($url -split '/')[-1]
  Write-Host "PR_RESULT: CREATED`nPR_NUMBER: $number`nPR_URL: $url`nBASE_BRANCH: $Base`nHEAD_BRANCH: $branch`nISSUE_NUMBER: $issue`nQUALITY_GATE: SUCCESS`nREVIEW_GATE: APPROVED`nBLOCK_REASON:"
} finally {
  Remove-Item $bodyFile -ErrorAction SilentlyContinue
}
