$ErrorActionPreference = "Stop"
$runId = "$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))-$PID"
$project = "timing-jeju-smoke-$runId"
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

function Invoke-CanonicalManifest([string] $database, [string] $beforePath = "") {
  Invoke-SqlFile $database "/docker-entrypoint-initdb.d/001_auth_compat.sql"
  foreach ($entry in $manifest.immutablePrefix) {
    Invoke-SqlFile $database (Resolve-MountedMigration $entry.path)
  }
  foreach ($entry in $manifest.canonicalSuffix) {
    if ($entry.path -eq $beforePath) { break }
    if ($entry.initSlot -eq "056") { continue }
    if ($entry.initSlot -eq "055") {
      Invoke-SqlFile $database "/docker-entrypoint-initdb.d/055_location_cutover_group.sql"
      continue
    }
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
  $cutoverCheck = @'
do $$
begin
  if timing_jeju_planner_private.user_location_guard_purge_revision() <> '20260918000018'
     or exists (select 1 from timing_jeju_planner_private.user_location_residue_counts()
                where residue_count <> 0) then
    raise exception 'location cutover verification failed';
  end if;
end;
$$;
'@
  Invoke-ComposePostgres @(
    "psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=1",
    "--username", "timing_jeju_test", "--dbname", "timing_jeju_test", "--command", $cutoverCheck
  )
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
  # Historical #109 cleanup uses the pre-cutover schema; current races run in PG16/17 integration.
  Invoke-CanonicalManifest $concurrencyDatabase "supabase/migrations/20260918000017_user_location_write_guard_purge.sql"
  Invoke-SqlFile $concurrencyDatabase "/queries/database_concurrency_contract.sql"
  Write-Host "[Docker] canonical migration fresh/upgrade/fingerprint/concurrency 성공"
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
