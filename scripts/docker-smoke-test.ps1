$ErrorActionPreference = "Stop"
$project = "timing-jeju-smoke"
$originDevelopDatabase = "canonical_origin_develop_upgrade"
$concurrencyDatabase = "canonical_migration_concurrency"
$manifestPath = "supabase/migrations/manifest.json"
$manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json
$composeSource = Get-Content "compose.test.yml" -Raw

function Invoke-ComposePostgres([string[]] $arguments) {
  & docker compose -p $project -f compose.test.yml exec -T postgres @arguments
  if ($LASTEXITCODE -ne 0) { throw "PostgreSQL container command failed: $($arguments -join ' ')" }
}

function Resolve-MountedMigration([string] $repositoryPath) {
  $escaped = [regex]::Escape("./$repositoryPath")
  $matched = [regex]::Match(
    $composeSource,
    "$escaped`:(/docker-entrypoint-initdb[.]d/[0-9]{3}_[^:]+[.]sql):ro"
  )
  if (-not $matched.Success) { throw "Manifest migration mount not found: $repositoryPath" }
  return $matched.Groups[1].Value
}

function Invoke-SqlFile([string] $database, [string] $file) {
  Invoke-ComposePostgres @(
    "psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1",
    "--username", "timing_jeju_test", "--dbname", $database, "--file", $file
  )
}

function Invoke-CanonicalManifest([string] $database) {
  Invoke-SqlFile $database "/docker-entrypoint-initdb.d/001_auth_compat.sql"
  foreach ($entry in $manifest.immutablePrefix) {
    Invoke-SqlFile $database (Resolve-MountedMigration $entry.path)
  }
  foreach ($entry in $manifest.canonicalSuffix) {
    Invoke-SqlFile $database (Resolve-MountedMigration $entry.path)
  }
}

function Get-CanonicalFingerprint([string] $database) {
  $result = & docker compose -p $project -f compose.test.yml exec -T postgres `
    psql --no-psqlrc --tuples-only --no-align --set ON_ERROR_STOP=1 `
    --username timing_jeju_test --dbname $database `
    --file /queries/canonical_migration_fingerprint.sql
  if ($LASTEXITCODE -ne 0) { throw "Canonical fingerprint failed: $database" }
  return ($result -join "`n").Trim()
}

function Cleanup-Smoke {
  foreach ($database in @($originDevelopDatabase, $concurrencyDatabase)) {
    try {
      & docker compose -p $project -f compose.test.yml exec -T postgres `
        dropdb --username timing_jeju_test --if-exists --force $database | Out-Null
    } catch { }
  }
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

  $healthy = $false
  for ($attempt = 1; $attempt -le 60; $attempt++) {
    try {
      $response = Invoke-RestMethod -Uri "http://127.0.0.1:$smokeApiPort/actuator/health" -TimeoutSec 3
      if ($response.status -eq "UP") { $healthy = $true; break }
    } catch { Start-Sleep -Seconds 2 }
  }
  if (-not $healthy) {
    docker compose -p $project -f compose.test.yml logs --no-color api postgres
    throw "[Docker] Health Check 실패"
  }
  Write-Host "[Docker] Health Check 성공"

  $freshFingerprint = Get-CanonicalFingerprint "timing_jeju_test"
  Invoke-ComposePostgres @("createdb", "--username", "timing_jeju_test", $originDevelopDatabase)

  # canonical_schedule_50_51_upgrade: immutable #50 and additive #51 remain separate
  # history entries inside canonical_origin_develop_upgrade.
  Invoke-CanonicalManifest $originDevelopDatabase
  $upgradeFingerprint = Get-CanonicalFingerprint $originDevelopDatabase
  if ($upgradeFingerprint -ne $freshFingerprint) {
    throw "Fresh and origin/develop-upgrade schema/ACL fingerprints differ"
  }

  Invoke-ComposePostgres @("createdb", "--username", "timing_jeju_test", $concurrencyDatabase)
  Invoke-CanonicalManifest $concurrencyDatabase
  Invoke-SqlFile $concurrencyDatabase "/queries/database_concurrency_contract.sql"
  Write-Host "[Docker] canonical migration fresh/upgrade/fingerprint/concurrency 성공"
} finally {
  Cleanup-Smoke
}
