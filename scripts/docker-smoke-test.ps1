$ErrorActionPreference = "Stop"
$runId = "$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))-$PID"
$project = "timing-jeju-smoke-$runId"

function Cleanup-Smoke {
  try {
    docker compose -p $project -f compose.test.yml down -v --remove-orphans | Out-Null
  } catch {
    Write-Warning "[Docker] smoke project 정리에 실패했습니다: $project"
  }
  try {
    docker image rm "${project}-api:latest" | Out-Null
  } catch {
    Write-Verbose "[Docker] 제거할 smoke API 이미지가 없습니다: ${project}-api:latest"
  }
}

function Resolve-ApiPort([string[]]$PublishedPorts) {
  $entries = @(
    $PublishedPorts |
      ForEach-Object { $_.Trim() } |
      Where-Object { $_ -ne "" }
  )
  if ($entries.Count -ne 1) {
    throw "[Docker] API publish port가 하나가 아닙니다."
  }
  if ($entries[0] -notmatch ':(\d+)$') {
    throw "[Docker] API publish port 형식이 올바르지 않습니다."
  }

  $port = 0
  if (-not [int]::TryParse($Matches[1], [ref]$port)) {
    throw "[Docker] API publish port가 정수가 아닙니다."
  }
  if ($port -lt 1 -or $port -gt 65535) {
    throw "[Docker] API publish port 범위가 올바르지 않습니다."
  }
  return $port
}

try {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker가 설치되지 않았습니다." }
  docker info | Out-Null
  docker compose -p $project -f compose.test.yml up -d --build
  $publishedPorts = @(docker compose -p $project -f compose.test.yml port api 8080)
  $apiPort = Resolve-ApiPort $publishedPorts
  for ($attempt = 1; $attempt -le 60; $attempt++) {
    try {
      $response = Invoke-RestMethod -Uri "http://127.0.0.1:$apiPort/actuator/health" -TimeoutSec 3
      if ($response.status -eq "UP") {
        Write-Host "[Docker] Health Check 성공"
        exit 0
      }
    } catch { Start-Sleep -Seconds 2 }
  }
  docker compose -p $project -f compose.test.yml logs --no-color api postgres
  throw "[Docker] Health Check 실패"
} finally {
  Cleanup-Smoke
}
