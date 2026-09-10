"""CLI fetch inventory와 custom atomic ledger의 원문 byte/SHA를 검증한다."""
from pathlib import Path
import hashlib
import json
import sys


def verify(root: Path, fetched: Path) -> None:
    manifest = json.loads((root / "supabase/migrations/manifest.json").read_text())
    entries = manifest["immutablePrefix"] + manifest["canonicalSuffix"]
    expected_names = set()
    for entry in entries:
        source = root / entry["path"]
        name = source.name
        expected_names.add(name)
        candidate = fetched / name
        if not candidate.is_file():
            raise ValueError(f"fetched migration missing: {name}")
        # CLI-applied ordinary history may normalize statement separators when
        # fetched. The custom atomic release rows (017+) must reconstruct the
        # immutable source exactly so they remain auditable and replayable.
        if name[:14] >= "20260918000017":
            if candidate.read_bytes() != source.read_bytes():
                raise ValueError(f"fetched migration differs: {name}")
            if hashlib.sha256(candidate.read_bytes()).hexdigest() != entry["sha256"]:
                raise ValueError(f"fetched migration checksum differs: {name}")
        elif not candidate.read_bytes().strip():
            raise ValueError(f"fetched migration is empty: {name}")
    actual_names = {path.name for path in fetched.glob("*.sql")}
    if actual_names != expected_names:
        raise ValueError("fetched migration inventory differs")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_supabase_fetched_migrations.py FETCHED_DIR")
    verify(Path(__file__).resolve().parents[1], Path(sys.argv[1]).resolve())
