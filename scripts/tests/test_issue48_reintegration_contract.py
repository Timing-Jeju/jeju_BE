import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATION_NAME = "20260908000000_trip_place_preference_contract.sql"
MIGRATION = ROOT / "supabase" / "migrations" / MIGRATION_NAME
STORE = (
    ROOT
    / "services/spring-api/src/main/java/com/timingjeju/api/domain/trip/adapter"
    / "JdbcTripPlacePreferencesStore.java"
)


def compact(value: str) -> str:
    return re.sub(r"\s+", " ", value.lower()).strip()


class Issue48ReintegrationContractTest(unittest.TestCase):
    def test_place_preferences_store_consumes_canonical_trip_mutation_coordinator(self):
        source = STORE.read_text(encoding="utf-8")

        self.assertIn("TripAggregateMutationCoordinator", source)
        self.assertIn("mutations.executeMonotonic(", source)
        self.assertNotIn(" for update", source.lower())
        self.assertNotRegex(source, r"revision\s*=\s*revision\s*\+\s*1")

    def test_migration_is_append_only_and_blocks_data_api_privileges(self):
        sql = compact(MIGRATION.read_text(encoding="utf-8"))

        self.assertIn("enable row level security", sql)
        self.assertIn(
            "revoke all on table public.trip_place_preferences from public, anon, authenticated",
            sql,
        )
        for function_name in (
            "validate_trip_place_preference_contract()",
            "validate_trip_place_preference_calendar_change()",
        ):
            self.assertIn(
                f"revoke all on function public.{function_name} from public, anon, authenticated",
                sql,
            )
        self.assertNotIn("grant execute", sql)

    def test_every_compose_and_smoke_sequence_mounts_issue48_as_slot_043(self):
        mount = f"./supabase/migrations/{MIGRATION_NAME}:/docker-entrypoint-initdb.d/043_trip_place_preference_contract.sql:ro"
        for relative in ("compose.yml", "compose.test.yml", "docker-compose.yml"):
            self.assertIn(mount, (ROOT / relative).read_text(encoding="utf-8"))

        smoke = (ROOT / "scripts/docker-smoke-test.sh").read_text(encoding="utf-8")
        self.assertGreaterEqual(
            smoke.count("/docker-entrypoint-initdb.d/043_trip_place_preference_contract.sql"),
            2,
        )


if __name__ == "__main__":
    unittest.main()
