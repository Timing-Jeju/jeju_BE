from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class Issue114DevelopIntegrationTest(unittest.TestCase):
    def test_firebase_adapter와_mode20_contract가_함께_유지된다(self):
        firebase_adapter = (
            ROOT
            / "services/spring-api/src/main/java/com/timingjeju/api/global/push/firebase"
            / "FirebasePushMessageSender.java"
        )
        self.assertTrue(firebase_adapter.is_file())

        for gate_name in ("quality-gate.sh", "quality-gate.ps1"):
            gate = (ROOT / "scripts" / gate_name).read_text(encoding="utf-8")
            self.assertIn("--mode 20", gate, gate_name)
            self.assertNotIn("--mode 16", gate, gate_name)

        migration_names = {
            path.name for path in (ROOT / "supabase/migrations").glob("*.sql")
        }
        self.assertIn(
            "20260904000000_push_device_notification_preferences.sql",
            migration_names,
        )
        self.assertIn(
            "20260904000001_push_notification_server_writer_boundary.sql",
            migration_names,
        )


if __name__ == "__main__":
    unittest.main()
