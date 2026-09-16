import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DELETION_NAMES = (
    "20260919000000_account_deletion_requests.sql",
    "20260919010000_account_deletion_worker_runtime.sql",
    "20260919020000_account_deletion_retention_contract.sql",
    "20260919030000_account_deletion_security_correction.sql",
    "20260919040000_account_deletion_worker_fencing.sql",
)


class AccountDeletionMigrationOrderTest(unittest.TestCase):
    def test_generation_and_rls_precede_account_deletion_in_all_compose_manifests(self):
        manifest = json.loads((ROOT / "supabase/migrations/manifest.json").read_text())
        records = manifest["canonicalSuffix"]
        names = [Path(record["path"]).name for record in records]
        generation_end = names.index("20260918000031_generation_leg_precision.sql")
        rls_index = names.index("20260918000032_rls_auto_enable_execute_boundary.sql")
        self.assertEqual(generation_end + 1, rls_index)
        self.assertEqual(list(DELETION_NAMES), names[rls_index + 1:rls_index + 6])
        self.assertEqual(
            ["071", "072", "073", "074", "075"],
            [record["initSlot"] for record in records[rls_index + 1:rls_index + 6]],
        )
        self.assertEqual(
            ["20260918000032_rls_auto_enable_execute_boundary.sql",
             *DELETION_NAMES[:-1]],
            [Path(record["dependencies"][0]).name for record in records[rls_index + 1:rls_index + 6]],
        )
        for compose_name in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            with self.subTest(compose=compose_name):
                text = (ROOT / compose_name).read_text()
                self.assertNotIn("20260918000022_rls_auto_enable_execute_boundary.sql", text)
                for slot, name in zip(range(71, 76), DELETION_NAMES):
                    self.assertIn(f"{name}:/docker-entrypoint-initdb.d/{slot:03}_", text)


if __name__ == "__main__":
    unittest.main()
