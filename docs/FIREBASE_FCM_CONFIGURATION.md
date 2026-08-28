# Firebase FCM 발송 설정

Spring의 FCM 발송 경계는 Firebase Admin Java SDK `9.10.0`을 고정해 사용합니다. 공개 API와 DB migration은 추가하지 않으며 `application.push`의 provider-neutral port를 `global.push.firebase` adapter가 구현합니다.

기본값과 로컬·CI는 `FCM_ENABLED=false`입니다. 이때 disabled sender만 생성되고 ADC 조회나 외부 네트워크 호출을 하지 않습니다. 활성화할 때는 아래 값이 모두 준비되어야 하며 하나라도 없거나 잘못되면 애플리케이션 시작이 실패합니다.

- `FCM_ENABLED=true`
- `FIREBASE_PROJECT_ID`: Firebase project ID
- Google Application Default Credentials 또는 secret mount가 가리키는 `GOOGLE_APPLICATION_CREDENTIALS`
- `FCM_CONNECT_TIMEOUT`(기본 2초, 100ms..10초), `FCM_READ_TIMEOUT`과 `FCM_WRITE_TIMEOUT`(기본 5초, 100ms..30초)

서비스 계정 JSON 원문이나 base64, private key, access/refresh token은 환경 설정 파일, fixture, 로그, Actuator에 넣지 않습니다. provider 응답 body, registration token, notification/data 원문도 로그와 metric에 기록하지 않습니다. 실제 Firebase endpoint를 사용하는 검증은 명시적으로 격리한 환경에서만 수행합니다.

## Docker Compose ADC secret

기본 `compose.yml`과 CI용 `compose.test.yml`은 FCM을 비활성화하며 자격 증명을 mount하지 않습니다. 로컬에서 실제 provider 검증을 명시적으로 수행할 때만 `FIREBASE_PROJECT_ID`와 서비스 계정 JSON의 절대 경로인 `FIREBASE_CREDENTIALS_FILE`을 로컬 비추적 환경에 설정하고 canonical launcher를 사용합니다. JSON 원문이나 base64를 `.env` 또는 Compose environment에 넣지 않습니다.

Compose의 file-backed secret은 bind mount이므로 service secret의 `uid/gid/mode` 값을 적용하지 않습니다. 따라서 `compose.fcm.yml`은 적용되지 않는 mode를 선언하지 않습니다. macOS와 Linux 모두 host credential은 launcher를 실행하는 현재 사용자 소유의 symlink가 아닌 regular file과 정확히 `0400` 또는 `0600` permission이어야 합니다. group/world permission은 허용하지 않으며 host에서 `10001:10001`로 chown하지 않습니다.

launcher는 portable Python `os.stat` preflight로 absolute path, regular/non-symlink, 현재 UID/GID와 owner-only permission을 먼저 확인합니다. preflight는 파일 내용을 읽거나 경로·내용을 출력하지 않으며 조건이 하나라도 다르면 Compose를 호출하지 않습니다.

```sh
chmod 0600 "$FIREBASE_CREDENTIALS_FILE"
./scripts/run-firebase-compose.sh
```

`run-firebase-compose.sh`는 인자를 받지 않고 내부에서 `validate_firebase_credential_file.py`를 실행하며, credential preflight가 성공한 뒤에만 저장소의 고정 `compose.yml`과 `compose.fcm.yml`로 API를 `up -d --build`합니다. raw Compose opt-in은 사용하지 않습니다. 이 launcher 외부의 subcommand, Compose file, project directory나 command 환경변수는 실행에 반영하지 않습니다.

`compose.fcm.yml`의 one-shot root init service만 검증된 file-backed secret을 읽기 전용 mount합니다. init은 내용을 출력하지 않고 Docker-managed `firebase-credential` volume에 복사한 뒤 소유권을 runtime `spring`의 `10001:10001`, permission을 `0400`으로 고정합니다. API는 init의 `service_completed_successfully` 이후에만 시작하며 host secret을 직접 mount하지 않습니다.

API는 managed volume만 `/run/secrets/timing-jeju-firebase`에 read-only mount합니다. ADC canonical path는 `/run/secrets/timing-jeju-firebase/service-account.json`이며 `GOOGLE_APPLICATION_CREDENTIALS`에는 이 고정 경로만 전달됩니다. 기본 실행과 CI에는 이 override를 결합하지 않습니다.

FCM message ID는 provider의 `ACCEPTED` 증거일 뿐 단말의 `DELIVERED` 증거가 아닙니다. 명시적 429/5xx와 request byte 미전송이 증명된 pre-connect 실패만 재시도 가능하며, 일반 SDK transport 실패와 post-write timeout/reset/unexpected EOF는 `ACCEPTANCE_UNKNOWN` terminal 결과로 취급합니다.

한 durable delivery attempt는 FCM HTTP v1 raw POST를 정확히 한 번만 실행합니다. Firebase Admin의 message model과 ADC 설정은 사용하지만 SDK 내부의 투명 503 retry 경로는 사용하지 않으며, authorized Google HTTP request의 retry 횟수와 I/O retry를 모두 0으로 고정합니다. 재시도 판단과 새 attempt 번호는 notification worker만 소유합니다. `Retry-After`는 RFC 9110 delay-seconds 또는 HTTP-date 한 값만 수용하고 malformed·과거·0·복수 값은 hint 없이 fail-closed합니다.
