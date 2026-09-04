#!/usr/bin/env python3
"""Verify generated frontend client coverage without network access."""

import json
import sys
from pathlib import Path

HTTP_METHODS = {"get", "post", "put", "patch", "delete"}
REQUIRED_OPERATIONS = {
    "tripScheduleRead",
    "tripScheduleItemCreate",
    "tripAccommodationsCreate",
    "tripAccommodationsUpdate",
    "tripAccommodationsDelete",
}


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print("usage: verify_frontend_api_client_artifact.py OPENAPI OUTPUT EXPECTED_COUNT", file=sys.stderr)
        return 2
    openapi_path, output_directory, expected_count = Path(argv[1]), Path(argv[2]), int(argv[3])
    document = json.loads(openapi_path.read_text(encoding="utf-8"))
    operation_ids = [
        operation.get("operationId")
        for path_item in (document.get("paths") or {}).values()
        for method, operation in path_item.items()
        if method.lower() in HTTP_METHODS and isinstance(operation, dict)
    ]
    invalid = [value for value in operation_ids if not isinstance(value, str) or not value]
    duplicates = sorted({value for value in operation_ids if operation_ids.count(value) > 1})
    index = (output_directory / "index.ts").read_text(encoding="utf-8")
    missing = sorted(value for value in operation_ids if isinstance(value, str) and value not in index)
    required_missing = sorted(REQUIRED_OPERATIONS - set(operation_ids))
    if len(operation_ids) != expected_count or invalid or duplicates or missing or required_missing:
        print(
            "TypeScript client operation 검증 실패: "
            f"count={len(operation_ids)}, invalid={len(invalid)}, duplicates={duplicates}, "
            f"missing={missing}, requiredMissing={required_missing}",
            file=sys.stderr,
        )
        return 1
    print(f"TypeScript frontend client 검사 성공: {len(operation_ids)} operations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
