#!/bin/sh
set -eu

if [ "$#" -ne 0 ]; then
  echo "FCM Compose cleanup은 인자를 받지 않습니다." >&2
  exit 2
fi

api_name=$(docker ps -a --filter 'name=^/timing-jeju-fcm-api-1$' --format '{{.Names}}')
case "$api_name" in
  "") ;;
  "timing-jeju-fcm-api-1") docker container rm -f timing-jeju-fcm-api-1 >/dev/null ;;
  *) echo "Unexpected FCM API container query result." >&2; exit 1 ;;
esac

init_name=$(docker ps -a --filter 'name=^/timing-jeju-fcm-firebase-credential-init-1$' --format '{{.Names}}')
case "$init_name" in
  "") ;;
  "timing-jeju-fcm-firebase-credential-init-1") docker container rm -f timing-jeju-fcm-firebase-credential-init-1 >/dev/null ;;
  *) echo "Unexpected FCM credential init container query result." >&2; exit 1 ;;
esac

credential_volume=$(docker volume ls -q --filter 'name=^timing-jeju-fcm_firebase-credential$')
case "$credential_volume" in
  "") ;;
  "timing-jeju-fcm_firebase-credential") docker volume rm timing-jeju-fcm_firebase-credential >/dev/null ;;
  *) echo "Unexpected FCM credential volume query result." >&2; exit 1 ;;
esac
