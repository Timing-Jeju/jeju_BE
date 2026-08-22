# 외부 API snapshot 저장 계약

## 책임과 호출 경계

외부 API importer는 공개 Controller나 Repository를 직접 호출하지 않고 `SnapshotStoreService`를 사용합니다. Spring만 원문과 DB를 소유하며 프론트와 FastAPI에는 원문 payload, 사용자 token, 외부 API key를 전달하지 않습니다. 공급자 parser와 retention 삭제 작업은 이 계약 밖의 후속 기능입니다.

## 입력·hash·크기

- HTTP transport에서 압축 해제가 끝난 byte sequence만 받습니다.
- decompressed payload 상한은 정확히 2 MiB(2,097,152 bytes)입니다. 초과 입력은 hash·redaction·DB 호출 전에 거부합니다.
- `payload_hash`는 canonical JSON이 아니라 decompressed 원문 bytes의 SHA-256 소문자 64자리입니다. 공백이나 byte encoding이 다르면 hash도 다릅니다.
- 문자 payload는 UTF-8만 허용합니다. 잘못된 UTF-8은 원문을 보존하지 않고 rejected 감사 행으로 분류합니다.
- `request_hash`는 `SnapshotStoreService`가 호출하는 단일 `snapshot-request-v1` canonical schema로만 생성합니다. provider/service/operation/scope/page/privacy-canonical metadata를 UTF-8 byte length로 구분해 SHA-256을 계산합니다. metadata map key는 중첩 위치 container 안에서도 재귀 정렬하고 list 순서는 보존합니다. service key·인증값·원문 URL·사용자 PII는 위치 container 내부에서도 동일 marker로 치환합니다. 정밀 위치 좌표 값은 request metadata와 로그에 저장하지 않지만 서로 다른 요청을 구분하기 위한 hash 입력에만 사용한 뒤 즉시 폐기합니다. 후속 provenance는 hash를 재계산하지 않고 `SnapshotSaveResult.requestFingerprint`를 그대로 사용합니다.

## redaction

단일 registry `snapshot-redaction-v2`는 대소문자와 `_`·`-`·`.` 차이를 정규화하여 다음 계열을 재귀적으로 제거합니다.

- `serviceKey`, API key, Authorization, cookie, token, secret, password
- email, 전화번호, 사용자·계정·기기 ID, 이름·닉네임, 주소
- latitude/longitude, lat/lng, 좌표·GPS·map 좌표와 사용자 위치 접두사가 붙은 정밀 좌표
- 요청 URL/URI 및 자유 문자열의 HTTP(S) URL, Bearer, email

JSON은 key 정렬 후 JSONB로 저장합니다. XML은 namespace-aware parser의 element·attribute `localName`을 같은 registry로 검사하고 DTD·외부 entity를 비활성화합니다. namespace 선언과 안전한 namespace 값은 보존합니다. `placeName`, `categoryName`, `contactless`처럼 민감 alias와 일부 문자열만 겹치는 안전한 유사키는 보존합니다. text는 key/value, query string, header 형태를 정제합니다. malformed JSON/XML/UTF-8은 `rejected`, binary는 `ignored`로 기록하고 `raw_payload`를 NULL로 둡니다. 정제 전후 payload와 metadata를 로그나 예외에 넣지 않습니다.

## 상태·멱등성·보존

- 저장 시작 상태는 `received`이며 malformed/binary만 즉시 `rejected`/`ignored`입니다.
- `received → parsed | rejected | ignored | tombstoned` 전환만 허용하고 terminal 상태는 되돌리거나 서로 바꿀 수 없습니다.
- 같은 run/operation/request/page/payload hash는 동시에 저장해도 한 행이며 기존 snapshot ID를 반환합니다. 저장 payload가 의미적으로 다르면 안전한 hash collision 오류로 중단합니다.
- 저장과 전환은 각각 한 SQL statement로 처리하여 부분 행이나 중간 marker를 남기지 않습니다.
- parsed/tombstoned payload는 전환 시점부터 30일, rejected/ignored/received payload는 7일 보존 metadata를 가집니다.
- 후속 retention 작업은 `purge_after` 이후에만 `raw_payload=NULL`, `purged_at`을 같은 statement로 기록합니다. scope, hash, parser/redaction version, payload size와 상태 감사 정보는 유지합니다.
- TAGO 도착정보는 HTTP response의 압축 해제된 exact bytes를 snapshot store에 한 번 전달하고 parser도
  같은 byte 배열을 소비합니다. provider 오류 `97`처럼 body가 있는 실패는 `rejected`로 감사하지만,
  timeout·HTTP 429처럼 response body를 받지 못한 실패에는 가짜 오류 payload를 만들지 않습니다.
- migration 이전 legacy 행의 `payload_size_bytes`는 당시 JSONB text 표현의 UTF-8 크기로 backfill하고 `redaction_version=legacy-unversioned`, `payload_format=LEGACY_UNKNOWN`으로 구분합니다. 현재 상태·오류는 `initial_parse_status`·`initial_error_code`로 보수적으로 backfill합니다. 신규 행만 transport의 decompressed byte-exact 크기와 최초 분류를 보장합니다.

## DB와 권한

운영 변경은 `supabase/migrations/20260813010000_external_snapshot_storage.sql`만 사용하며 Flyway를 도입하지 않습니다. snapshot scope는 `data_import_runs`의 provider/service/operation/scope와 일치해야 합니다. RLS를 유지하고 `anon`·`authenticated`에는 직접 권한을 주지 않습니다.

Docker v1→latest 검증은 010 적용 뒤 legacy snapshot을 만든 다음 011을 적용합니다. 기존 행의 payload·identity를 보존하면서 nullable payload, payload byte size·format·최초 분류 backfill, `legacy-unversioned`, `purged_at`, trigger와 직접 권한 차단이 모두 유효한지 검사합니다.

멱등 replay는 동일 byte hash만으로 결정하지 않습니다. payload format, 최초 분류 상태·오류 코드, redaction 결과와 version까지 같아야 하며 malformed JSON과 binary처럼 byte가 같아도 의미가 다르면 hash collision으로 거부합니다. 저장 후 현재 상태가 parsed 등 terminal로 전환되어도 최초 분류가 같으면 진짜 duplicate는 같은 snapshot id로 replay합니다.
