param([string]$Repo)
$ErrorActionPreference = "Stop"
$mode = if ($env:REMOTE_SETUP_MODE) { $env:REMOTE_SETUP_MODE } else { "dry-run" }
if ($mode -eq "dry-run") {
  Write-Host "[dry-run] main/develop에 PR·승인·대화 해결·CI·삭제·force push Ruleset을 적용합니다."
  Write-Host "[dry-run] main Release 경로는 CI release-policy Job이 추가 검증합니다."
  exit 0
}
gh auth status | Out-Null
if (-not $Repo) { $Repo = gh repo view --json nameWithOwner --jq .nameWithOwner }
foreach ($branch in @("main", "develop")) {
  $name = "protect-$branch"
  $payload = [ordered]@{
    name=$name; target="branch"; enforcement="active"
    conditions=@{ref_name=@{include=@("refs/heads/$branch"); exclude=@()}}
    rules=@(
      @{type="deletion"}, @{type="non_fast_forward"},
      @{type="pull_request"; parameters=@{required_approving_review_count=1; dismiss_stale_reviews_on_push=$true; require_code_owner_review=$false; require_last_push_approval=$true; required_review_thread_resolution=$true}},
      @{type="required_status_checks"; parameters=@{strict_required_status_checks_policy=$true; do_not_enforce_on_create=$false; required_status_checks=@(@{context="quality-gate"})}}
    )
  }
  $file = [System.IO.Path]::GetTempFileName()
  try {
    $payload | ConvertTo-Json -Depth 10 | Set-Content -Encoding utf8 $file
    $existing = gh api "repos/$Repo/rulesets" --jq ".[] | select(.name == `"$name`") | .id"
    if ($existing) { gh api --method PUT "repos/$Repo/rulesets/$existing" --input $file | Out-Null }
    else { gh api --method POST "repos/$Repo/rulesets" --input $file | Out-Null }
  } finally { Remove-Item $file -ErrorAction SilentlyContinue }
}
gh api --method PATCH "repos/$Repo" -F allow_auto_merge=false | Out-Null
