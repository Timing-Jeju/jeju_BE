from __future__ import annotations

import re
import subprocess
import sys
from collections.abc import Callable


MINIMUM_COMPOSE_VERSION = (2, 24, 4)
VERSION_PATTERN = re.compile(
    r"v?([0-9]+)\.([0-9]+)\.([0-9]+)(?:-desktop\.[1-9][0-9]*)?"
)


class ComposeVersionError(ValueError):
    pass


def parse_compose_version(raw_version: str) -> tuple[int, int, int]:
    match = VERSION_PATTERN.fullmatch(raw_version.strip())
    if match is None:
        raise ComposeVersionError("Docker Compose version 형식이 올바르지 않습니다.")
    try:
        version = tuple(int(part) for part in match.groups())
    except ValueError as error:
        raise ComposeVersionError("Docker Compose version 형식이 올바르지 않습니다.") from error
    if version < MINIMUM_COMPOSE_VERSION:
        raise ComposeVersionError("Docker Compose 2.24.4 이상이 필요합니다.")
    return version


def validate_installed_compose(
    *, run: Callable[..., subprocess.CompletedProcess[str]] = subprocess.run
) -> None:
    try:
        result = run(
            ("docker", "compose", "version", "--short"),
            capture_output=True,
            text=True,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise ComposeVersionError("Docker Compose version을 확인할 수 없습니다.") from error
    if result.returncode != 0:
        raise ComposeVersionError("Docker Compose version을 확인할 수 없습니다.")
    parse_compose_version(result.stdout)


def main() -> int:
    try:
        validate_installed_compose()
    except ComposeVersionError as error:
        print(str(error), file=sys.stderr)
        return 1
    print("Docker Compose version preflight 성공.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
