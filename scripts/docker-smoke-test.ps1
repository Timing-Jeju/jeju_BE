$ErrorActionPreference = "Stop"
$runId = "$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))-$PID"
$project = "timing-jeju-smoke-$runId"

function Cleanup-Smoke {
  $cleanupErrors = [System.Collections.Generic.List[string]]::new()

  docker compose -p $project -f compose.test.yml down -v --remove-orphans | Out-Null
  if ($LASTEXITCODE -ne 0) {
    $cleanupErrors.Add("smoke project 정리 실패")
  }

  $imageBeforeCleanup = @(
    docker image ls --filter "reference=${project}-api:latest" --quiet
  )
  if ($LASTEXITCODE -ne 0) {
    $cleanupErrors.Add("smoke API 이미지 상태 확인 실패")
  } elseif ($imageBeforeCleanup.Count -gt 0) {
    docker image rm "${project}-api:latest" | Out-Null
    if ($LASTEXITCODE -ne 0) {
      $cleanupErrors.Add("smoke API 이미지 정리 실패")
    }
  }

  try {
    Assert-NoSmokeResidue
  } catch {
    $cleanupErrors.Add($_.Exception.Message)
  }

  if ($cleanupErrors.Count -gt 0) {
    throw "[Docker] cleanup 검증 실패 ($project): $($cleanupErrors -join '; ')"
  }
}

function Assert-DockerSucceeded([int]$ExitCode, [string]$Operation) {
  if ($ExitCode -ne 0) {
    throw "[Docker] $Operation 실패 (exit=$ExitCode)"
  }
}

function Assert-NoSmokeResidue {
  $containerResidue = @(docker compose -p $project -f compose.test.yml ps -aq)
  Assert-DockerSucceeded $LASTEXITCODE "container residue 확인"
  $networkResidue = @(
    docker network ls --filter "label=com.docker.compose.project=$project" --quiet
  )
  Assert-DockerSucceeded $LASTEXITCODE "network residue 확인"
  $volumeResidue = @(
    docker volume ls --filter "label=com.docker.compose.project=$project" --quiet
  )
  Assert-DockerSucceeded $LASTEXITCODE "volume residue 확인"
  $imageResidue = @(
    docker image ls --filter "reference=${project}-api:latest" --quiet
  )
  Assert-DockerSucceeded $LASTEXITCODE "image residue 확인"

  if ($containerResidue.Count -gt 0 -or $networkResidue.Count -gt 0 -or
      $volumeResidue.Count -gt 0 -or $imageResidue.Count -gt 0) {
    throw "[Docker] 현재 smoke project resource residue가 남았습니다."
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

$scriptFailure = $null
$cleanupFailure = $null
try {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker가 설치되지 않았습니다." }
  docker info | Out-Null
  Assert-DockerSucceeded $LASTEXITCODE "Docker daemon 확인"
  docker compose -p $project -f compose.test.yml up -d --build
  Assert-DockerSucceeded $LASTEXITCODE "Compose 실행"
  $publishedPorts = @(docker compose -p $project -f compose.test.yml port api 8080)
  Assert-DockerSucceeded $LASTEXITCODE "API publish port 조회"
  $apiPort = Resolve-ApiPort $publishedPorts
  $healthy = $false
  for ($attempt = 1; $attempt -le 60; $attempt++) {
    try {
      $response = Invoke-RestMethod -Uri "http://127.0.0.1:$apiPort/actuator/health" -TimeoutSec 3
      if ($response.status -eq "UP") {
        Write-Host "[Docker] Health Check 성공"
        $healthy = $true
        break
      }
    } catch { Start-Sleep -Seconds 2 }
  }
  if (-not $healthy) {
    docker compose -p $project -f compose.test.yml logs --no-color api postgres
    throw "[Docker] Health Check 실패"
  }
} catch {
  $scriptFailure = $_
} finally {
  try {
    Cleanup-Smoke
  } catch {
    $cleanupFailure = $_
  }
}

if ($null -ne $scriptFailure) {
  throw $scriptFailure
}
if ($null -ne $cleanupFailure) {
  throw $cleanupFailure
}
