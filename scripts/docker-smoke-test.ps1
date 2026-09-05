$ErrorActionPreference = "Stop"
$project = "timing-jeju-smoke"

function Cleanup-Smoke {
  docker compose -p $project -f compose.test.yml down -v --remove-orphans | Out-Null
}

try {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "Docker가 설치되지 않았습니다." }
  docker info | Out-Null
  $requestedSmokeApiPort = if ($env:TIMING_JEJU_SMOKE_API_PORT) { $env:TIMING_JEJU_SMOKE_API_PORT } else { "28080" }
  $validatedSmokeApiPort = & py -3 scripts/validate_smoke_api_port.py $requestedSmokeApiPort
  if ($LASTEXITCODE -ne 0) { throw "Docker smoke API port 검증 실패" }
  $smokeApiPort = $validatedSmokeApiPort.Trim()
  $env:TIMING_JEJU_SMOKE_API_PORT = $smokeApiPort
  docker compose -p $project -f compose.test.yml up -d --build
  for ($attempt = 1; $attempt -le 60; $attempt++) {
    try {
      $response = Invoke-RestMethod -Uri "http://127.0.0.1:$smokeApiPort/actuator/health" -TimeoutSec 3
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
