"""역사 TTL 동시성 전용 DB에 적용할 검증된 016 이전 SQL 목록을 만든다."""

import hashlib
import json
from pathlib import Path
import re

CUTOFF = "20260918000016_planned_route_reference_integrity.sql"


def historical_migrations(root: Path, manifest: dict) -> list[Path]:
    root = root.resolve()
    entries = manifest["immutablePrefix"] + manifest["canonicalSuffix"]
    paths = []
    versions = []
    for entry in entries:
        relative = entry["path"]
        if not re.fullmatch(r"supabase/migrations/[0-9]{14}_[a-z0-9_]+\.sql", relative):
            raise ValueError("historical migration path is invalid")
        path = root / relative
        versions.append(path.name[:14])
        if path.name > CUTOFF:
            continue
        if not path.resolve().is_relative_to(root / "supabase/migrations"):
            raise ValueError("historical migration path is outside repository")
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise ValueError("historical migration checksum mismatch")
        paths.append(path)
    if versions != sorted(set(versions)) or not paths or paths[-1].name != CUTOFF:
        raise ValueError("historical migration boundary is invalid")
    return paths


if __name__ == "__main__":
    repository = Path(__file__).resolve().parents[1]
    manifest = json.loads((repository / "supabase/migrations/manifest.json").read_text())
    for migration in historical_migrations(repository, manifest):
        print(migration)
