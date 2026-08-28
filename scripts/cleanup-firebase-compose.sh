#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
  echo "FCM Compose cleanup은 인자를 받지 않습니다." >&2
  exit 2
fi

SCRIPT_DIR=$(CDPATH= cd -P "$(dirname "$0")" && pwd)
ROOT=$(CDPATH= cd -P "$SCRIPT_DIR/.." && pwd)

docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" stop api firebase-credential-init
docker compose --project-name timing-jeju-fcm --project-directory "$ROOT" -f "$ROOT/compose.yml" -f "$ROOT/compose.fcm.yml" rm -f api firebase-credential-init
exec docker volume rm timing-jeju-fcm_firebase-credential
