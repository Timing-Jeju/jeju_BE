from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path

from scripts.validate_firebase_credential_file import CredentialFileError
from scripts.validate_firebase_credential_file import validate_credential_file


class FirebaseCredentialPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.credential = Path(self.temporary_directory.name) / "firebase.json"
        self.credential.write_text("test-only-placeholder", encoding="utf-8")
        self.credential.chmod(0o600)

    def test_owner_only_regular_file은_expected_runtime_owner가_읽을_수_있다(self):
        validate_credential_file(
            self.credential,
            expected_uid=os.getuid(),
            expected_gid=os.getgid(),
        )

    def test_missing_directory_symlink은_regular_credential로_허용하지_않는다(self):
        missing = self.credential.with_name("missing.json")
        directory = self.credential.parent / "credential-directory"
        directory.mkdir()
        symlink = self.credential.with_name("credential-link.json")
        symlink.symlink_to(self.credential)

        for scenario, path in (
            ("missing", missing),
            ("directory", directory),
            ("symlink", symlink),
        ):
            with self.subTest(scenario=scenario), self.assertRaises(CredentialFileError):
                validate_credential_file(
                    path,
                    expected_uid=os.getuid(),
                    expected_gid=os.getgid(),
                )

    def test_relative_path는_compose와_다른_file을_가리킬_수_있어_거부한다(self):
        with self.assertRaises(CredentialFileError):
            validate_credential_file(
                Path("firebase.json"),
                expected_uid=os.getuid(),
                expected_gid=os.getgid(),
            )

    def test_group_or_world_permission과_owner_unreadable_mode를_거부한다(self):
        for mode in (0o640, 0o604, 0o200):
            with self.subTest(mode=oct(mode)):
                self.credential.chmod(mode)
                with self.assertRaises(CredentialFileError):
                    validate_credential_file(
                        self.credential,
                        expected_uid=os.getuid(),
                        expected_gid=os.getgid(),
                    )

    def test_wrong_uid_or_gid는_container_readable_owner_contract를_위반한다(self):
        for expected_uid, expected_gid in (
            (os.getuid() + 1, os.getgid()),
            (os.getuid(), os.getgid() + 1),
        ):
            with self.subTest(uid=expected_uid, gid=expected_gid), self.assertRaises(
                CredentialFileError
            ):
                validate_credential_file(
                    self.credential,
                    expected_uid=expected_uid,
                    expected_gid=expected_gid,
                )


if __name__ == "__main__":
    unittest.main()
