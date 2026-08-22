# 외부 API 실행 설정

## 범위

Spring API는 TourAPI·TAGO·TMAP·KMA의 활성 여부와 접속 설정을 typed configuration으로 읽고, 공통 `ExternalApiExecutor`가 안전한 HTTP 실행 경계를 제공합니다. 구체 provider DTO·parser와 importer는 후속 Issue에서 구현합니다. FastAPI와 프론트에는 provider key나 원천 요청 설정을 전달하지 않습니다.

운영 Secret Manager 제품, workload identity/IAM, rotation과 rollback 절차는 배포 ADR Issue #63에서 확정합니다. 현재 Issue는 로컬·CI 환경변수 계약과 애플리케이션 시작 검증만 소유합니다.

## 환경변수

각 provider는 같은 다섯 가지 값을 사용합니다.

| Provider | 활성 여부 | 비밀값 | Base URL | 연결 timeout | 응답 timeout |
| --- | --- | --- | --- | --- | --- |
| TourAPI | `TOUR_API_ENABLED` | `TOUR_API_API_KEY` | `TOUR_API_BASE_URL` | `TOUR_API_CONNECT_TIMEOUT` | `TOUR_API_READ_TIMEOUT` |
| TAGO | `TAGO_ENABLED` | `TAGO_API_KEY` | `TAGO_BASE_URL` | `TAGO_CONNECT_TIMEOUT` | `TAGO_READ_TIMEOUT` |
| TMAP | `TMAP_ENABLED` | `TMAP_API_KEY` | `TMAP_BASE_URL` | `TMAP_CONNECT_TIMEOUT` | `TMAP_READ_TIMEOUT` |
| KMA | `KMA_ENABLED` | `KMA_API_KEY` | `KMA_BASE_URL` | `KMA_CONNECT_TIMEOUT` | `KMA_READ_TIMEOUT` |

TAGO 정류장 도착정보 importer도 위 TAGO 설정을 공유한다. 별도 key나 URL 환경변수를 만들지 않으며
`ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList` operation은 코드 allowlist에 고정한다.
20~30초 cache TTL과 최대 120초 stale 허용 범위는 application 계약이며 환경변수로 완화하지 않는다.

- 기본 활성값은 모두 `false`입니다. 비활성 provider는 key 없이 시작하고 client 설정 bean을 만들지 않습니다.
- provider를 활성화하면 API key와 정확한 provider Base URL이 필수입니다. 공백, `changeme`, `replace-me`, `your-*`, `<...>`, `${...}` placeholder는 실제 key로 인정하지 않습니다.
- TourAPI·TAGO·KMA의 `*_API_KEY`에는 공공데이터포털에서 제공하는 **decoded 원문 key**만 넣습니다. `%2B`, `%2F`, `%3D`처럼 이미 percent-encoded된 입력은 시작 시 거부합니다. 후속 query adapter는 typed credential 경계의 UTF-8 encoder를 사용해 `+`, `/`, `=`를 각각 `%2B`, `%2F`, `%3D`로 **정확히 한 번 percent-encoding**한 값을 `serviceKey` query에 조립해야 하며, 반환된 값을 다시 인코딩하면 안 됩니다.
- `TMAP_API_KEY`는 URL query 값이 아니라 TMAP 인증 header 원문입니다. typed credential은 TMAP 값을 `headerValue()`로만 제공하고 query encoder 사용을 거부합니다. TMAP header 값에는 공공데이터 `serviceKey` percent-encoding 정책을 적용하지 않습니다.
- 연결 timeout은 100ms 이상 10초 이하, 응답 timeout은 100ms 이상 30초 이하입니다. 기본값은 각각 2초와 5초입니다.
- `local` 또는 `local-hs256` profile을 단독으로 사용할 때만 공식 allowlist URL의 HTTP를 허용합니다. 기본·`prod`·`production`·CI를 포함한 나머지 환경은 HTTPS만 허용합니다. local profile과 다른 profile을 함께 활성화하면 시작에 실패합니다.
- Base URL은 user info, query, fragment, 임의 port와 provider 경계를 벗어난 host/path를 허용하지 않습니다.

허용 기준은 다음과 같습니다.

| Provider | Host | Base path |
| --- | --- | --- |
| TourAPI | `apis.data.go.kr` | `/B551011/KorService2` |
| TAGO | `apis.data.go.kr` | `/1613000` |
| TMAP | `apis.openapi.sk.com` | `/` |
| KMA | `apis.data.go.kr` | `/1360000/VilageFcstInfoService_2.0` |

## 로컬 사용 순서

1. 추적되지 않는 `.env`에 사용할 provider의 `*_ENABLED=true`를 설정합니다.
2. 같은 provider의 `*_API_KEY`에 실제 발급값을 넣습니다. TourAPI·TAGO·KMA는 decoded 원문 key, TMAP은 header 원문을 사용하고 Base URL과 timeout은 `.env.example` 값을 복사합니다.
3. `SPRING_PROFILES_ACTIVE=local`로 실행합니다.
4. `/actuator/info`의 `externalApis`에서 활성 여부만 확인합니다. 이 응답에는 key, Base URL, timeout이 포함되지 않습니다.

설정 객체와 client 설정 객체의 문자열 표현은 key를 `[REDACTED]`로 가립니다. 애플리케이션 오류, 로그, Actuator와 문서에 실제 key, `Authorization` 값 또는 `serviceKey` query를 기록하지 않습니다.

## 공통 HTTP 실행 계약

- 연결 timeout 기본값은 2초, 응답 timeout 기본값은 5초이며 한 논리 호출의 전체 budget은 8초입니다. 전체 budget에는 최대 3회의 시도와 retry 대기가 모두 포함됩니다.
- retry는 `GET`에만 적용합니다. HTTP `408`, `429`, `502`, `503`, `504`와 connection reset만 재시도하며, 그 밖의 method·status·전송 오류는 한 번의 시도로 종료합니다.
- 일반 retry는 200ms 기반, 2초 상한의 full jitter를 사용합니다. `Retry-After`의 delta seconds와 RFC 1123 날짜를 인식하되 대기는 최대 5초이고 전체 budget을 넘기지 않습니다.
- circuit breaker는 provider의 고정 service·operation 단위로 분리합니다. 최근 20회 중 최소 10회가 수집되고 실패율이 50% 이상이면 30초 동안 open하며, 이후 half-open 3회가 모두 성공해야 닫습니다.
- redirect를 따라가지 않습니다. 요청 path는 안전한 상대 경로만 받고 target은 시작 시 검증한 Base URL의 동일 scheme·host·port와 하위 path에서만 조립합니다.
- 성공 응답은 operation이 선언한 JSON 또는 XML content type만 허용합니다. `gzip`은 압축을 해제하면서 읽고 **압축 해제 후 2 MiB**를 넘으면 stream을 즉시 닫고 실패합니다. body 전체가 timeout·전체 budget 안에 완료된 후에만 parser로 전달합니다.
- provider 응답 파싱 실패, 허용하지 않은 content type/encoding, body 초과, redirect, timeout, 회로 open과 전송 실패는 안정적인 `ExternalApiFailureCode`로 분류합니다. provider 원문과 parser 원인 message는 예외에 포함하지 않습니다.
- 분류된 `ExternalApiException`은 원본 transport·body·runtime·resource close 예외를 `cause`나 `suppressed`에 보존하지 않습니다. 예외 생성 단계에서 suppression을 비활성화하므로 try-with-resources의 close 실패도 첨부되지 않습니다. 원본 예외는 timeout·connection reset 등 로컬 분류에만 사용하며 message·`toString()`·전체 stack trace나 별도 로그로 전달하지 않습니다.
- metric `timingjeju.external.api.requests`는 논리 호출 횟수와 latency를 기록합니다. tag는 enum으로 고정된 `provider`, `service`, `operation`, 제한된 `result`만 사용하며 URL, query, status 원문, key, payload와 사용자 값은 넣지 않습니다.

이 공통 모듈은 공개 Controller, DB, schema, provider DTO를 추가하지 않습니다. 후속 adapter는 `ExternalApiOperation`에 등록된 고정 operation과 `ExternalApiRequest`만 사용하고 raw HTTP client를 별도로 만들지 않습니다.
