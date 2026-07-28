from __future__ import annotations

import argparse
import os
import subprocess
from collections.abc import Iterable


CONTRACT_PATHS = {
    "docs/designs/timing-jeju-fastapi-mcp-contract.md",
    "docs/designs/timing-jeju-spring-fastapi-integration-contract.md",
}
SHARED_SERVICE_PATHS = {
    ".github/workflows/ci.yml",
    "compose.yml",
    "compose.test.yml",
    "scripts/quality-gate.sh",
    "scripts/quality-gate.ps1",
}


def classify_paths(paths: Iterable[str]) -> dict[str, bool]:
    normalized = {path.strip().removeprefix("./") for path in paths if path.strip()}
    contract = any(path in CONTRACT_PATHS or path.startswith("contracts/") for path in normalized)
    shared = any(
        path in SHARED_SERVICE_PATHS or path.startswith("scripts/ci/")
        for path in normalized
    )

    return {
        "spring": shared
        or contract
        or any(path.startswith("services/spring-api/") for path in normalized)
        or any(path.startswith("scripts/docker-smoke-test.") for path in normalized),
        "fastapi": shared
        or contract
        or any(path.startswith("services/fastapi-mcp/") for path in normalized),
        "contract": contract,
    }


def changed_paths(base: str, head: str) -> list[str]:
    if not base or set(base) == {"0"}:
        return [".github/workflows/ci.yml"]
    result = subprocess.run(
        ["git", "diff", "--name-only", base, head],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.splitlines()


def main() -> int:
    parser = argparse.ArgumentParser(description="변경 경로별 CI 실행 범위를 계산합니다.")
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()

    result = classify_paths(changed_paths(args.base, args.head))
    output = os.environ.get("GITHUB_OUTPUT")
    lines = [f"{name}={str(enabled).lower()}" for name, enabled in result.items()]
    if output:
        with open(output, "a", encoding="utf-8") as file:
            file.write("\n".join(lines) + "\n")
    else:
        print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
