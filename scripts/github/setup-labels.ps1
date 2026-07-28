param([string]$Repo)
$ErrorActionPreference = "Stop"
$mode = if ($env:REMOTE_SETUP_MODE) { $env:REMOTE_SETUP_MODE } else { "dry-run" }
if (-not $Repo -and $mode -eq "apply") { $Repo = gh repo view --json nameWithOwner --jq .nameWithOwner }
$labels = @(
  @("type:feat","1f883d","새 기능"), @("type:fix","d1242f","버그 수정"),
  @("type:build","8250df","빌드 또는 의존성"), @("type:chore","6e7781","유지보수"),
  @("type:docs","0969da","문서"), @("type:style","bf8700","동작 없는 스타일 변경"),
  @("type:refactor","fb8f44","리팩터링"), @("type:test","0a7c72","테스트"),
  @("type:release","a40e26","출시"), @("priority:P0","b60205","즉시 대응"),
  @("priority:P1","d93f0b","높은 우선순위"), @("priority:P2","fbca04","보통 우선순위"),
  @("priority:P3","c2e0c6","낮은 우선순위"), @("status:ready","0e8a16","개발 준비 완료"),
  @("status:in-progress","1d76db","진행 중"), @("status:blocked","b60205","차단됨"),
  @("status:needs-review","5319e7","리뷰 필요"), @("status:changes-requested","d93f0b","수정 요청"),
  @("status:approved","0e8a16","승인됨")
)
foreach ($label in $labels) {
  if ($mode -eq "dry-run") { Write-Host "gh label create $($label[0]) --color $($label[1]) --description `"$($label[2])`" --force" }
  else { gh label create $label[0] --repo $Repo --color $label[1] --description $label[2] --force }
}
