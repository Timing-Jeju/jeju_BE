"""Issue #225: the final schema hashes only the seven public planned identity fields."""

import hashlib
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "supabase/migrations"
TARGET = MIGRATIONS / "20260918000017_planned_route_request_hash_policy.sql"
FIELDS = [
    "route.anchor_contract_version",
    "route.trip_plan_id::text",
    "route.schedule_version_id::text",
    "route.origin_anchor_kind",
    "route.origin_anchor_id::text",
    "route.destination_anchor_kind",
    "route.destination_anchor_id::text",
]


class PlannedRouteHashPolicyTest(unittest.TestCase):
    def test_final_hash_uses_exactly_seven_public_identity_fields_without_coordinates(self):
        definitions = []
        for path in sorted(MIGRATIONS.glob("*.sql")):
            definitions.extend(re.findall(
                r"create(?: or replace)? function timing_jeju_planner_private\.planned_route_request_hash\(.*?as \$\$(.*?)\$\$;",
                path.read_text(), re.S | re.I,
            ))
        self.assertTrue(definitions)
        body = definitions[-1]
        self.assertNotIn("ST_AsEWKT", body, "final route hash still retains coordinate-derived input")
        arguments = re.search(r"source_identity_digest\((.*?)\);", body, re.S).group(1)
        self.assertEqual(FIELDS, [value.strip() for value in arguments.split(",")])

    def test_historical_migrations_are_not_rewritten(self):
        for name, expected in (
            ("20260918000015_planned_route_snapshot_provenance.sql", "205737aca9a9b70408a1e2e12115dd95c00d36791998eef71091c7445d33130c"),
            ("20260918000016_planned_route_reference_integrity.sql", "fc08d48553dc6edc05f30ac0789e5c5c618d46024ff8f8359487bf899f34d233"),
        ):
            self.assertEqual(expected, hashlib.sha256((MIGRATIONS / name).read_bytes()).hexdigest())

    def test_forward_rehash_is_atomic_and_only_suspends_the_named_guard(self):
        sql = TARGET.read_text().lower()
        self.assertIn("begin;", sql)
        self.assertIn("lock table public.mobility_route_snapshots in access exclusive mode", sql)
        self.assertIn("security invoker set search_path = ''", sql)
        self.assertIn("disable trigger trg_mobility_route_planned_provenance", sql)
        self.assertIn("enable trigger trg_mobility_route_planned_provenance", sql)
        self.assertNotRegex(sql, r"disable trigger (all|user)")
        self.assertNotIn("session_replication_role", sql)
        self.assertRegex(sql, r"update public.mobility_route_snapshots snapshot\s+set request_hash\s*=")
        self.assertTrue(sql.rstrip().endswith("commit;"))


if __name__ == "__main__":
    unittest.main()
