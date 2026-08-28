# Firebase FCM 발송 설정

Spring의 FCM 발송 경계는 Firebase Admin Java SDK `9.10.0`을 고정해 사용합니다. 공개 API와 DB migration은 추가하지 않으며 `application.push`의 provider-neutral port를 `global.push.firebase` adapter가 구현합니다.

기본값과 로컬·CI는 `FCM_ENABLED=false`입니다. 이때 disabled sender만 생성되고 ADC 조회나 외부 네트워크 호출을 하지 않습니다. 활성화할 때는 아래 값이 모두 준비되어야 하며 하나라도 없거나 잘못되면 애플리케이션 시작이 실패합니다.

- `FCM_ENABLED=true`
- `FIREBASE_PROJECT_ID`: Firebase project ID
- Google Application Default Credentials 또는 secret mount가 가리키는 `GOOGLE_APPLICATION_CREDENTIALS`
- `FCM_CONNECT_TIMEOUT`(기본 2초, 100ms..10초), `FCM_READ_TIMEOUT`과 `FCM_WRITE_TIMEOUT`(기본 5초, 100ms..30초)

서비스 계정 JSON 원문이나 base64, private key, access/refresh token은 환경 설정 파일, fixture, 로그, Actuator에 넣지 않습니다. provider 응답 body, registration token, notification/data 원문도 로그와 metric에 기록하지 않습니다. 실제 Firebase endpoint를 사용하는 검증은 명시적으로 격리한 환경에서만 수행합니다.

## Docker Compose ADC secret

기본 `compose.yml`과 CI용 `compose.test.yml`은 FCM을 비활성화하며 자격 증명을 mount하지 않습니다. 로컬에서 실제 provider 검증을 명시적으로 수행할 때만 `FIREBASE_PROJECT_ID`와 서비스 계정 JSON의 절대 경로인 `FIREBASE_CREDENTIALS_FILE`을 로컬 비추적 환경에 설정하고 `compose.fcm.yml`을 추가합니다. JSON 원문이나 base64를 `.env` 또는 Compose environment에 넣지 않습니다.

`compose.fcm.yml`은 FCM을 활성화하고 host 파일을 Compose secret으로 읽기 전용 `0400` mount합니다. 컨테이너 안의 ADC canonical path는 `/run/secrets/timing-jeju-firebase-service-account.json`이며 `GOOGLE_APPLICATION_CREDENTIALS`에는 이 고정 경로만 전달됩니다. 기본 실행과 CI에는 이 override를 결합하지 않습니다.

```sh
docker compose -f compose.yml -f compose.fcm.yml up --build
```

FCM message ID는 provider의 `ACCEPTED` 증거일 뿐 단말의 `DELIVERED` 증거가 아닙니다. 명시적 429/5xx와 request byte 미전송이 증명된 pre-connect 실패만 재시도 가능하며, 일반 SDK transport 실패와 post-write timeout/reset/unexpected EOF는 `ACCEPTANCE_UNKNOWN` terminal 결과로 취급합니다.

한 durable delivery attempt는 FCM HTTP v1 raw POST를 정확히 한 번만 실행합니다. Firebase Admin의 message model과 ADC 설정은 사용하지만 SDK 내부의 투명 503 retry 경로는 사용하지 않으며, authorized Google HTTP request의 retry 횟수와 I/O retry를 모두 0으로 고정합니다. 재시도 판단과 새 attempt 번호는 notification worker만 소유합니다. `Retry-After`는 RFC 9110 delay-seconds 또는 HTTP-date 한 값만 수용하고 malformed·과거·0·복수 값은 hint 없이 fail-closed합니다.
