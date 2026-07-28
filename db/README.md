# Timing Jeju Local DB

## Start

```bash
docker compose up -d postgres
```

The local database is exposed on `localhost:5433` to avoid collisions with an existing local PostgreSQL on `5432`.

```text
database: timing_jeju
user: timing_jeju
password: timing_jeju
host: localhost
port: 5433
```

## Files

- `init/001_extensions.sql`: enables `pgcrypto` and `postgis`
- `init/002_schema.sql`: creates the core RDB/PostGIS schema
- `init/003_seed_fixtures.sql`: seeds the current Jeju fixture data and one demo trip
- `queries/smoke_check.sql`: verifies extensions, seed counts, spatial lookup, arrival snapshots, and demo trip timeline

## Smoke Check

```bash
docker exec timing-jeju-postgres psql -U timing_jeju -d timing_jeju -f /queries/smoke_check.sql
```

Expected high-level result:

- extensions: `pgcrypto`, `postgis`
- table count: `28`
- auth users: `1`
- user profiles: `1`
- places: `3`
- stops: `6`
- weather grid points: `1`
- MCP compute call logs: `0`
- demo trip token: `demo-east-jeju`

## Reset

This deletes the local database volume and reruns all init scripts on the next start.

```bash
docker compose down -v
docker compose up -d postgres
```
