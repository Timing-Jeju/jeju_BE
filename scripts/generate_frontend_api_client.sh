#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
OPENAPI_PATH="${1:-${REPOSITORY_ROOT}/services/spring-api/build/openapi/openapi.json}"
OUTPUT_DIRECTORY="${2:-${REPOSITORY_ROOT}/services/spring-api/build/frontend-api-client}"
ARCHIVE_PATH="${REPOSITORY_ROOT}/services/spring-api/build/distributions/timing-jeju-frontend-api-client.tgz"

if [[ ! -f "${OPENAPI_PATH}" ]]; then
  echo "OpenAPI artifact가 없습니다: ${OPENAPI_PATH}" >&2
  echo "먼저 services/spring-api에서 ./gradlew openApiDocs를 실행하세요." >&2
  exit 1
fi

python3 "${SCRIPT_DIR}/validate_openapi_frontend_readiness.py" "${OPENAPI_PATH}" --mode 25

npx -y \
  -p typescript@6.0.3 \
  -p @hey-api/openapi-ts@0.99.0 \
  openapi-ts \
  -i "${OPENAPI_PATH}" \
  -o "${OUTPUT_DIRECTORY}"

python3 - "${OPENAPI_PATH}" "${OUTPUT_DIRECTORY}" <<'PY'
import json
import pathlib
import sys

openapi_path = pathlib.Path(sys.argv[1])
output_directory = pathlib.Path(sys.argv[2])
document = json.loads(openapi_path.read_text(encoding="utf-8"))
operation_ids = {
    operation["operationId"]
    for path_item in document["paths"].values()
    for method, operation in path_item.items()
    if method.lower() in {"get", "post", "put", "patch", "delete"}
}
index = (output_directory / "index.ts").read_text(encoding="utf-8")
missing = sorted(operation_id for operation_id in operation_ids if operation_id not in index)
if len(operation_ids) != 25 or missing:
    raise SystemExit(
        f"TypeScript client operation 검증 실패: count={len(operation_ids)}, missing={missing}"
    )
for operation_id in (
    "tripAccommodationsCreate",
    "tripAccommodationsUpdate",
    "tripAccommodationsDelete",
):
    if operation_id not in index:
        raise SystemExit(f"숙소 client operation이 없습니다: {operation_id}")
print(f"TypeScript frontend client 검사 성공: {len(operation_ids)} operations")
PY

mkdir -p "$(dirname "${ARCHIVE_PATH}")"
tar -czf "${ARCHIVE_PATH}" -C "$(dirname "${OUTPUT_DIRECTORY}")" "$(basename "${OUTPUT_DIRECTORY}")"
echo "TypeScript frontend client: ${OUTPUT_DIRECTORY}"
echo "Release archive: ${ARCHIVE_PATH}"
