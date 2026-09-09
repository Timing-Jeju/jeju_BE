"""017·018의 검증된 외곽 transaction만 합치는 전용 오프라인 생성기."""
from pathlib import Path
import argparse
import hashlib
import json
import re

OUTPUT = "db/local-postgres/20260918000017_location_cutover_group.sql"
SOURCES = (
    ("20260918000017_user_location_write_guard_purge.sql", "3f1cb04a7a6f0b5577229eb4f9efc9a3e064b203e7423d3712b217bafa8fccbe"),
    ("20260918000018_revision_request_hash_audit.sql", "5bdd91f35b45a7bec2490a5e4c19597f7a2eab521da9889ff89599c887acdd33"),
 )


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
    manifest = json.loads((root / "supabase/migrations/manifest.json").read_text())
    entries = manifest["immutablePrefix"] + manifest["canonicalSuffix"]
    versions = [entry["path"].split("/")[-1][:14] for entry in entries
                if entry["path"].split("/")[-1][:14] < "20260918000017"]
    if versions != sorted(set(versions)) or any(not re.fullmatch(r"[0-9]{14}", version) for version in versions):
        raise ValueError("location cutover history manifest mismatch")
    expected = ",".join("'" + version + "'" for version in versions)
    preflight = """begin;
lock table supabase_migrations.schema_migrations in access exclusive mode;
do $history$
begin
  if (select array_agg(version::text order by version) from supabase_migrations.schema_migrations)
      is distinct from array[""" + expected + """]::text[]
     or to_regprocedure('timing_jeju_planner_private.user_location_guard_purge_revision()') is not null then
    raise exception using errcode = '23514', message = 'location cutover migration history mismatch';
  end if;
end;
$history$;
"""
    bodies = b"\n".join(body((root / "supabase/migrations" / name).read_bytes(), checksum)
                        for name, checksum in SOURCES)
    # Supabase CLI 2.116.0 owns this exact ledger shape. The grouped execution is
    # not a native per-file statement list, so record immutable checksum markers
    # instead of pretending that either original file ran independently.
    history = """
insert into supabase_migrations.schema_migrations(version, name, statements)
values
  ('20260918000017', 'user_location_write_guard_purge',
    array['-- grouped source sha256:""" + SOURCES[0][1] + """']::text[]),
  ('20260918000018', 'revision_request_hash_audit',
    array['-- grouped source sha256:""" + SOURCES[1][1] + """']::text[]);
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
