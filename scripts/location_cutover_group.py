"""017·018의 검증된 외곽 transaction만 합치는 전용 오프라인 생성기."""
from pathlib import Path
import argparse
import hashlib
import json
import re

OUTPUT = "db/local-postgres/20260918000017_location_cutover_group.sql"
PREFLIGHT_POLICY = "db/fingerprints/location_cutover_predecessors.json"
PREFLIGHT_POLICY_SHA256 = "2783da39be14d2b6ebfb312a8ad90c2c228e0a5e220a85f14df06acafceece9d"
SOURCES = (
    ("20260918000017_user_location_write_guard_purge.sql", "3f1cb04a7a6f0b5577229eb4f9efc9a3e064b203e7423d3712b217bafa8fccbe"),
    ("20260918000018_revision_request_hash_audit.sql", "5bdd91f35b45a7bec2490a5e4c19597f7a2eab521da9889ff89599c887acdd33"),
 )


def load_predecessor_policy(root: Path) -> dict:
    source = (root / PREFLIGHT_POLICY).read_bytes()
    if hashlib.sha256(source).hexdigest() != PREFLIGHT_POLICY_SHA256:
        raise ValueError("predecessor fingerprint policy mismatch")
    policy = json.loads(source)
    if set(policy) != {"schemaVersion", "canonicalQuery", "ledger", "allowedFingerprints"}:
        raise ValueError("predecessor fingerprint policy mismatch")
    if policy["schemaVersion"] != 1 or policy["canonicalQuery"].get("path") != (
        "db/queries/canonical_migration_fingerprint.sql"
    ):
        raise ValueError("predecessor fingerprint policy mismatch")
    if policy["ledger"] != {
        "columns": ["version:text:NO", "statements:ARRAY:YES", "name:text:YES"],
        "primaryKey": ["version"],
    }:
        raise ValueError("predecessor fingerprint policy mismatch")
    allowed = policy["allowedFingerprints"]
    if set(allowed) != {"16", "17"} or any(
        not values
        or values != sorted(set(values))
        or any(not re.fullmatch(r"[0-9a-f]{32}", value) for value in values)
        for values in allowed.values()
    ):
        raise ValueError("predecessor fingerprint policy mismatch")
    query = (root / policy["canonicalQuery"]["path"]).read_bytes()
    if hashlib.sha256(query).hexdigest() != policy["canonicalQuery"].get("sha256"):
        raise ValueError("canonical fingerprint query mismatch")
    return {
        "canonicalQuerySha256": policy["canonicalQuery"]["sha256"],
        "canonicalQuery": query,
        "ledger": policy["ledger"],
        "allowedFingerprints": allowed,
    }


def sql_array(values: list[str]) -> str:
    return "array[" + ",".join("'" + value + "'" for value in values) + "]::text[]"


def body(source: bytes, checksum: str) -> bytes:
    if hashlib.sha256(source).hexdigest() != checksum:
        raise ValueError("location cutover source mismatch")
    # Only these two pinned files are accepted. SQL bodies are never parsed or rewritten.
    first_line, remainder = source.split(b"\n", 1)
    if not first_line.startswith(b"-- Issue #223:") or not remainder.startswith(b"begin;\n") or not remainder.endswith(b"commit;\n"):
        raise ValueError("location cutover envelope mismatch")
    return first_line + b"\n" + remainder[len(b"begin;\n"):-len(b"commit;\n")]


def render(root: Path) -> bytes:
    bodies = [body((root / "supabase/migrations" / name).read_bytes(), checksum) for name, checksum in SOURCES]
    return b"-- Generated location cutover group; do not edit.\nbegin;\n" + b"\n".join(bodies) + b"\ncommit;\n"



def render_supabase(root: Path) -> bytes:
    policy = load_predecessor_policy(root)
    manifest = json.loads((root / "supabase/migrations/manifest.json").read_text())
    entries = manifest["immutablePrefix"] + manifest["canonicalSuffix"]
    versions = [entry["path"].split("/")[-1][:14] for entry in entries
                if entry["path"].split("/")[-1][:14] < "20260918000017"]
    if versions != sorted(set(versions)) or any(not re.fullmatch(r"[0-9]{14}", version) for version in versions):
        raise ValueError("location cutover history manifest mismatch")
    expected = sql_array(versions)
    expected_columns = sql_array(policy["ledger"]["columns"])
    expected_primary_key = sql_array(policy["ledger"]["primaryKey"])
    allowed_16 = sql_array(policy["allowedFingerprints"]["16"])
    allowed_17 = sql_array(policy["allowedFingerprints"]["17"])
    canonical_query = policy["canonicalQuery"]
    if not canonical_query.endswith(b";\n"):
        raise ValueError("canonical fingerprint query envelope mismatch")
    canonical_subquery = canonical_query[:-2].decode("utf-8")
    preflight = """begin;
lock table supabase_migrations.schema_migrations in access exclusive mode;
do $preflight$
declare
  server_major integer := current_setting('server_version_num')::integer / 10000;
  actual_predecessor_fingerprint text;
  allowed_predecessor_fingerprints text[];
begin
  if (select array_agg(column_name || ':' || data_type || ':' || is_nullable order by ordinal_position)
      from information_schema.columns
      where table_schema = 'supabase_migrations' and table_name = 'schema_migrations')
      is distinct from """ + expected_columns + """
     or (select array_agg(key_column.column_name::text order by key_column.ordinal_position)
         from information_schema.table_constraints constraint_record
         join information_schema.key_column_usage key_column
           on key_column.constraint_schema = constraint_record.constraint_schema
          and key_column.constraint_name = constraint_record.constraint_name
          and key_column.table_schema = constraint_record.table_schema
          and key_column.table_name = constraint_record.table_name
         where constraint_record.table_schema = 'supabase_migrations'
           and constraint_record.table_name = 'schema_migrations'
           and constraint_record.constraint_type = 'PRIMARY KEY')
        is distinct from """ + expected_primary_key + """ then
    raise exception using errcode = '23514', message = 'location cutover migration ledger shape mismatch';
  end if;

  if (select array_agg(version::text order by version) from supabase_migrations.schema_migrations)
      is distinct from """ + expected + """
     or to_regprocedure('timing_jeju_planner_private.user_location_guard_purge_revision()') is not null then
    raise exception using errcode = '23514', message = 'location cutover migration history mismatch';
  end if;

  select schema_acl_fingerprint into actual_predecessor_fingerprint
  from (
""" + canonical_subquery + """
  ) canonical_fingerprint;
  if server_major = 16 then
    allowed_predecessor_fingerprints := """ + allowed_16 + """;
  elsif server_major = 17 then
    allowed_predecessor_fingerprints := """ + allowed_17 + """;
  else
    raise exception using errcode = '23514', message = 'location cutover predecessor server major mismatch';
  end if;
  if actual_predecessor_fingerprint is null
     or not (actual_predecessor_fingerprint = any(allowed_predecessor_fingerprints)) then
    raise exception using errcode = '23514', message = 'location cutover predecessor schema fingerprint mismatch';
  end if;
end;
$preflight$;
"""
    bodies = b"\n".join(body((root / "supabase/migrations" / name).read_bytes(), checksum)
                        for name, checksum in SOURCES)
    # Version-only rows are enough for CLI discovery. Do not repair history in a second transaction.
    history = """
insert into supabase_migrations.schema_migrations(version)
values ('20260918000017'), ('20260918000018');
commit;
"""
    return preflight.encode() + bodies + history.encode()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--supabase", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    expected = render_supabase(root) if args.supabase else render(root)
    target = root / ("db/local-postgres/location_cutover_supabase.sql" if args.supabase else OUTPUT)
    if args.check:
        if not target.is_file() or target.read_bytes() != expected:
            raise SystemExit("location cutover group differs from verified sources")
    else:
        target.write_bytes(expected)


if __name__ == "__main__":
    main()
