#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
  echo "FCM Compose cleanup은 인자를 받지 않습니다." >&2
  exit 2
fi

if docker container inspect timing-jeju-fcm-api-1 >/dev/null 2>&1; then
  docker container rm -f timing-jeju-fcm-api-1 >/dev/null
fi
if docker container inspect timing-jeju-fcm-firebase-credential-init-1 >/dev/null 2>&1; then
  docker container rm -f timing-jeju-fcm-firebase-credential-init-1 >/dev/null
fi
if docker volume inspect timing-jeju-fcm_firebase-credential >/dev/null 2>&1; then
  docker volume rm timing-jeju-fcm_firebase-credential >/dev/null
fi
