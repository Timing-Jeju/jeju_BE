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

python3 "${SCRIPT_DIR}/validate_openapi_frontend_readiness.py" "${OPENAPI_PATH}" --mode 27

npx -y \
  -p typescript@6.0.3 \
  -p @hey-api/openapi-ts@0.99.0 \
  openapi-ts \
  -i "${OPENAPI_PATH}" \
  -o "${OUTPUT_DIRECTORY}"

python3 "${SCRIPT_DIR}/verify_frontend_api_client_artifact.py" \
  "${OPENAPI_PATH}" "${OUTPUT_DIRECTORY}" 27

mkdir -p "$(dirname "${ARCHIVE_PATH}")"
tar -czf "${ARCHIVE_PATH}" -C "$(dirname "${OUTPUT_DIRECTORY}")" "$(basename "${OUTPUT_DIRECTORY}")"
echo "TypeScript frontend client: ${OUTPUT_DIRECTORY}"
echo "Release archive: ${ARCHIVE_PATH}"
