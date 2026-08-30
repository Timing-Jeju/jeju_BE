#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
  echo "FCM Compose launcher는 인자를 받지 않습니다." >&2
  exit 2
fi

SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
ROOT=$(CDPATH= cd -P "$SCRIPT_DIR/.." && pwd)

python3 "$ROOT/scripts/validate_firebase_credential_file.py"
python3 "$ROOT/scripts/validate_docker_compose_version.py"
docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" stop api
docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" rm -f api
docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" up -d --build postgres
docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" run --rm --no-deps --name timing-jeju-fcm-firebase-credential-init-1 firebase-credential-init
exec docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" up -d --build --force-recreate --no-deps api
