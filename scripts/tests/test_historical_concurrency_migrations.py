"""현재 위치 비수집 DB와 역사 TTL 동시성 fixture의 실행 범위를 분리한다."""

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class HistoricalConcurrencyMigrationsTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location(
            "historical_concurrency_migrations", ROOT / "scripts/historical_concurrency_migrations.py"
        )
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def fixture(self, root):
        directory = root / "supabase/migrations"
        directory.mkdir(parents=True)
        entries = []
        for name in (
            "20260728000000_initial_public_schema.sql",
            "20260918000016_planned_route_reference_integrity.sql",
            "20260918000020_remove_user_location_runtime.sql",
        ):
            path = directory / name
            path.write_text("select 1;\n")
            entries.append({"path": str(path.relative_to(root)),
                            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
        return {"immutablePrefix": entries[:1], "canonicalSuffix": entries[1:]}

    def test_current_location_removal_is_excluded_and_order_is_preserved(self):
        """역사 fixture는 016까지만 적용하고 현재 020을 섞지 않는다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self.fixture(root)
            paths = self.module.historical_migrations(root, manifest)
            self.assertEqual(2, len(paths))
            self.assertEqual("20260918000016_planned_route_reference_integrity.sql", paths[-1].name)

    def test_tampered_historical_source_is_rejected(self):
        """manifest와 다른 역사 SQL을 실행 목록에 포함하지 않는다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self.fixture(root)
            (root / manifest["immutablePrefix"][0]["path"]).write_text("select 2;\n")
            with self.assertRaises(ValueError):
                self.module.historical_migrations(root, manifest)

    def test_missing_boundary_is_rejected(self):
        """016이 없는 불완전한 설치를 역사 검증으로 허용하지 않는다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self.fixture(root)
            manifest["canonicalSuffix"] = manifest["canonicalSuffix"][1:]
            with self.assertRaises(ValueError):
                self.module.historical_migrations(root, manifest)

    def test_external_or_duplicate_paths_are_rejected(self):
        """저장소 밖 경로와 중복 버전을 실행 목록으로 사용하지 않는다."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self.fixture(root)
            for invalid in ("../secret.sql", manifest["immutablePrefix"][0]["path"]):
                changed = json.loads(json.dumps(manifest))
                changed["canonicalSuffix"][0]["path"] = invalid
                with self.subTest(path=invalid), self.assertRaises(ValueError):
                    self.module.historical_migrations(root, changed)
