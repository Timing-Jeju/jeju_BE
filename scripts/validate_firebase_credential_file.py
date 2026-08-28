from __future__ import annotations

import os
import stat
import sys
from pathlib import Path


ALLOWED_MODES = frozenset((0o400, 0o600))


class CredentialFileError(ValueError):
    pass


def validate_credential_file(
    credential_file: Path,
    *,
    expected_uid: int | None = None,
    expected_gid: int | None = None,
) -> None:
    expected_uid = os.getuid() if expected_uid is None else expected_uid
    expected_gid = os.getgid() if expected_gid is None else expected_gid
    if not credential_file.is_absolute():
        raise CredentialFileError("FIREBASE_CREDENTIALS_FILE은 absolute path여야 합니다.")

    try:
        metadata = credential_file.lstat()
    except OSError as error:
        raise CredentialFileError("Firebase credential file을 확인할 수 없습니다.") from error

    if not stat.S_ISREG(metadata.st_mode):
        raise CredentialFileError("Firebase credential은 symlink가 아닌 regular file이어야 합니다.")
    if metadata.st_uid != expected_uid or metadata.st_gid != expected_gid:
        raise CredentialFileError(
            f"Firebase credential owner는 launcher user {expected_uid}:{expected_gid}여야 합니다."
        )

    permission = stat.S_IMODE(metadata.st_mode)
    if permission not in ALLOWED_MODES:
        raise CredentialFileError(
            "Firebase credential permission은 owner read 전용 0400 또는 owner read/write 0600이어야 합니다."
        )


def main() -> int:
    configured_path = os.environ.get("FIREBASE_CREDENTIALS_FILE", "").strip()
    if not configured_path:
        print("FIREBASE_CREDENTIALS_FILE이 필요합니다.", file=sys.stderr)
        return 1

    try:
        validate_credential_file(Path(configured_path))
    except CredentialFileError as error:
        print(str(error), file=sys.stderr)
        return 1

    print("Firebase credential preflight 성공: owner와 permission 계약을 확인했습니다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
