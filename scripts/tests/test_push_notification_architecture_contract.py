from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ARCHITECTURE_TEST = (
    ROOT
    / "services/spring-api/src/test/java/com/timingjeju/api/architecture/ArchitectureTest.java"
)


class PushNotificationArchitectureContractTest(unittest.TestCase):
    def test_notification_boundaries_have_exact_archunit_rules(self):
        source = ARCHITECTURE_TEST.read_text(encoding="utf-8")
        for fragment in (
            "푸시_알림_application은_provider_Spring_global_adapter에_의존하지_않는다",
            '"..application.notification.."',
            '"org.springframework.."',
            '"org.springframework.security.."',
            '"..global.notification.."',
            '"..application.push.."',
            '"com.google.firebase.."',
            "푸시_알림_domain은_Firebase_SpringSecurity_global_adapter에_의존하지_않는다",
            '"..domain.notification.."',
            "푸시_알림_JDBC_crypto_adapter는_application_port_방향을_유지한다",
            'haveSimpleName("JdbcPushNotificationStore")',
            'haveSimpleName("AesGcmRegistrationTokenProtector")',
            "RegistrationTokenProtectionFailure",
        ):
            with self.subTest(fragment=fragment):
                self.assertIn(fragment, source)

    def test_crypto_failure_type_is_provider_neutral_and_runtime_is_not_blanket_caught(self):
        application_failure = (
            ROOT
            / "services/spring-api/src/main/java/com/timingjeju/api/application/notification/RegistrationTokenProtectionFailure.java"
        )
        self.assertTrue(application_failure.is_file())
        service = (
            ROOT
            / "services/spring-api/src/main/java/com/timingjeju/api/application/notification/service/PushDeviceService.java"
        ).read_text(encoding="utf-8")
        self.assertIn("catch (RegistrationTokenProtectionFailure failure)", service)
        self.assertNotIn("catch (RuntimeException failure)", service)
        application_source = application_failure.read_text(encoding="utf-8")
        self.assertIn("super(null, null, false, false)", application_source)
        protector = (
            ROOT
            / "services/spring-api/src/main/java/com/timingjeju/api/application/notification/RegistrationTokenProtector.java"
        ).read_text(encoding="utf-8")
        compact_protector = " ".join(protector.split())
        self.assertIn(
            "ProtectedRegistrationToken protect(String registrationToken) "
            "throws RegistrationTokenProtectionFailure;",
            compact_protector,
        )
        self.assertIn(
            "String reveal(String ciphertext) throws RegistrationTokenProtectionFailure;",
            compact_protector,
        )
        adapter = (
            ROOT
            / "services/spring-api/src/main/java/com/timingjeju/api/global/notification/AesGcmRegistrationTokenProtector.java"
        ).read_text(encoding="utf-8")
        self.assertEqual(adapter.count("throw new RegistrationTokenProtectionFailure()"), 2)
        adapter_test = (
            ROOT
            / "services/spring-api/src/test/java/com/timingjeju/api/global/notification/AesGcmRegistrationTokenProtectorTest.java"
        ).read_text(encoding="utf-8")
        self.assertIn("crypto_provider실패도_typed경계밖으로", adapter_test)
        self.assertIn('new ProviderException("provider leaked")', adapter_test)
        service_test = (
            ROOT
            / "services/spring-api/src/test/java/com/timingjeju/api/application/notification/PushDeviceServiceTest.java"
        ).read_text(encoding="utf-8")
        self.assertIn("typed_crypto실패만_503_application오류로_변환", service_test)
        self.assertIn("programmer_RuntimeException은_503으로_숨기지_않고_그대로_전파", service_test)
        global_failure = (
            ROOT
            / "services/spring-api/src/main/java/com/timingjeju/api/global/notification/RegistrationTokenProtectionException.java"
        )
        self.assertFalse(global_failure.exists())


if __name__ == "__main__":
    unittest.main()
