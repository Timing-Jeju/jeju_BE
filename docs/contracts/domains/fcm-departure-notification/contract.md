# FCM 다음 목적지 출발 알림 계약

Issue #112의 실행 가능한 canonical 기준은 같은 디렉터리의 `contract.json`이다. 이 Issue는 공개 API, DB migration, 디바이스 token 저장, scheduler 또는 FCM adapter를 구현하지 않는다. 현재는 contract-ready일 뿐 implementation-ready가 아니며 production default-off다. 필요한 선행 구현이나 신호가 하나라도 없으면 **fail-closed**한다.

## 소유권과 영속 모델

Spring이 token registry, logical job, per-device delivery attempt, 취소, 발송, 재시도와 provider 접수 상태를 단독 소유한다. FastAPI는 token·credential·job·attempt·취소·발송·재시도·알림 상태에 관여하지 않는다.

Issue #115의 `notification_jobs`는 기기와 무관한 logical job 한 건이다. logical key는 `tripId + scheduleVersionId + tripItemId + tripLegId + notificationType + scheduledAt`이며 복수 기기여도 job을 늘리지 않는다. Issue #116의 `push_delivery_targets`는 claim commit 후 첫 pre-send preparation transaction에서 live eligibility를 재검사한 뒤 `(jobId, pushDeviceId)`별 닫힌 target snapshot과 현재 상태를 보존한다. claim transaction에는 target을 저장하지 않는다. `push_delivery_attempts`는 `(jobId, pushDeviceId, attemptNo)`별 단일 행이다. 같은 attempt key는 overwrite하지 않고 거부한다.

job 상태는 `PENDING|LEASED|RETRY|ACCEPTED|CANCELLED|DEAD`, terminal은 `ACCEPTED|CANCELLED|DEAD`다. 허용 전이는 정확히 `PENDING → LEASED|CANCELLED`, `RETRY → LEASED|CANCELLED|DEAD`, 유효 lease의 `LEASED → ACCEPTED|RETRY|CANCELLED|DEAD`, 만료 lease reclaim의 `LEASED → LEASED`뿐이다. `PENDING → DEAD`는 금지하며 pending expiry는 `CANCELLED/EXPIRED`다. attempt 단일 `status` closed enum은 `RESERVED|CALL_STARTED|ACCEPTED|RETRYABLE_FAILURE|PERMANENT_FAILURE|SKIPPED|ACCEPTANCE_UNKNOWN`이다. provider 호출 전 exact attempt row를 `RESERVED`로 insert·commit하고, 어떤 provider I/O보다 먼저 same row CAS로 lease owner·generation·fencing·unexpired lease를 확인해 `CALL_STARTED`에 갱신·commit한다. provider 결과도 그 `CALL_STARTED` row를 terminal status로 갱신하며 terminal 이후 immutable하다. 호출 직전 recheck 실패의 `SKIPPED`만 reservation 전에 terminal로 직접 저장한다. 예약 또는 marker CAS 실패 시 provider를 호출하지 않는다.

expired `LEASED`는 exact CAS로 새 owner/lease를 발급하고 fencing token만 증가시켜 `LEASED → LEASED`로 회수한다. generation, target snapshot과 attempt identity/row는 보존하며 old fencing token completion은 거부한다. 새 owner가 provider 호출 전에 기존 row를 복구한다. `RESERVED`면 같은 row를 `RETRYABLE_FAILURE`, `CALL_STARTED`면 같은 row를 `ACCEPTANCE_UNKNOWN` terminal/no-retry로 갱신한다. call marker 뒤 crash, write 뒤 result 전 crash, result 수신 뒤 completion 전 crash도 같은 규칙이다. retry는 새 attempt number로만 만든다. completion은 LEASED 상태, lease owner·만료 전 lease, generation, fencing token과 exact attempt key가 모두 일치할 때만 같은 attempt row와 target/job 전이를 원자 적용한다. stale/terminal/중복 mismatch는 write 없이 거부하지만, 세 번째 transient attempt 또는 next retry가 `expiresAt` 이상인 경우 현재 `RETRYABLE_FAILURE` attempt와 `DEAD` job을 같은 transaction에 보존한다.

claim과 preparation 사이 활성화된 기기는 snapshot에 포함하고 비활성화된 기기는 제외한다. snapshot 뒤 활성화된 기기는 현재 job에 추가하지 않는다. 각 target 호출 직전 사용자 설정, OS 권한, 최신 required 위치 동의와 device active를 다시 확인한다. device만 비활성이면 provider 호출 없이 `SKIPPED`, job-wide 철회/무효화면 남은 호출 없이 `CANCELLED`다.

preparation에서 eligible target이 0건이면 empty snapshot 저장과 job `CANCELLED/NO_ACTIVE_PUSH_TARGET` 전이를 같은 transaction에서 수행하고 attempt/provider 호출은 0회다. target closed 전이는 `UNATTEMPTED|RETRYABLE → RESERVED → IN_FLIGHT → ACCEPTED|RETRYABLE|ACCEPTANCE_UNKNOWN|PERMANENT_FAILURE` 및 `UNATTEMPTED|RETRYABLE → SKIPPED`만 허용한다. 호출 직전 inactive retry target은 provider 호출 없이 `currentAttemptNo + 1`의 새 exact terminal `SKIPPED` attempt를 insert하고 target `RETRYABLE → SKIPPED`와 job aggregation을 같은 CAS transaction에 반영한다. duplicate key나 stale CAS는 mutation/provider 호출 모두 0으로 거부한다.

provider completion은 이미 존재하는 exact `CALL_STARTED` attempt와 `IN_FLIGHT` target에만 허용한다. absent, `RESERVED`, terminal, wrong target marker, second completion과 CAS mismatch는 무수정 거부한다. 성공 completion은 attempt terminal CAS, target 전이와 job aggregation을 같은 transaction에서 적용한다. lease 계약의 세대 명칭은 `generation/expectedGeneration`이라는 single generation naming만 사용한다.

job 집계는 서로 배타적인 순서다. 취소가 먼저이고, `UNATTEMPTED|RESERVED|IN_FLIGHT` target이 있으면 `LEASED`, TTL·한도 안의 `RETRYABLE`이 있으면 `RETRY`, 그 뒤 모든 target이 terminal일 때 하나라도 `ACCEPTED`면 `ACCEPTED`, 없으면 `DEAD`다. `ACCEPTANCE_UNKNOWN`은 성공으로 승격하지 않는다.

## 출발 시각, 안전 여유와 시간대

`notifyAt = targetArrivalAt - expectedTravelDurationSeconds - safetyBufferMinutes`이고 `expiresAt = min(notifyAt + 15분, targetArrivalAt)`이다. 영속 필드 `scheduledAt`은 `notifyAt`의 정확한 alias이며 `notifyAt`, `scheduledAt`, `expiresAt` 모두 PostgreSQL `timestamptz`/UTC로 저장한다. trusted server/database clock의 `evaluatedAt`에 대해 `notifyAt > evaluatedAt`와 `expiresAt > evaluatedAt`가 모두 참일 때만 job을 만든다. 어느 하나라도 equality 또는 past이면 `do_not_create_and_do_not_send`, provider call 0회다. 이동 시간은 1..604800초 inclusive integer다. Issue #113의 `safetyBufferMinutes`는 분 단위 integer, 기본 10분, 0..120분 inclusive이며 0도 허용한다. 초 변환과 시각 뺄셈은 checked arithmetic이고 범위·type·overflow 위반은 fail-closed다.

근거는 활성 일정 버전, 다음 일정 항목과 mobility leg다. 저장은 UTC `timestamptz`, 표시는 IANA 여행 시간대다. 입력 instant의 offset과 zone이 다르면 예약하지 않는다. DST overlap은 earlier/later 어느 offset을 주어도 fail-closed이고 DST gap도 fail-closed다. 휴대폰 background 실행에는 의존하지 않는다.

`safetyBufferMinutes` 변경은 preference version CAS, 이전 generation 무효화, 이전 미발송 job 취소, 새 preference 저장, `notifyAt` 재계산, 새 logical job 생성(이미 만료면 omission 기록)을 한 transaction에서 순서대로 수행한다. 10→30분과 30→0분 모두 같은 규칙이며 concurrent version mismatch는 아무것도 변경하지 않고 fail-closed한다. 이미 `CALL_STARTED`인 attempt 증거는 지우지 않고 위 ambiguity 복구 규칙을 적용한다.

## 사용자 표시 메시지와 data

앱 종료 상태에서도 OS가 표시하도록 **notification + data**를 쓴다. Android는 priority `high`와 `collapse_key=canonicalCollapseKey`를 사용한다. APNs는 alert+sound, `apns-expiration=epochSeconds(sendAttemptAt + ttlSeconds)`, `apns-collapse-id=canonicalCollapseKey`를 사용한다. 제목은 `다음 장소로 출발할 시간이에요`, 본문은 `{현지 출발 권장 시각}까지 {다음 장소명}(으)로 출발하세요.`다. 제목은 80 UTF-8 byte, 본문은 256 UTF-8 byte 이하다. control character나 초과가 있으면 둘 다 `출발 알림` / `앱을 열어 다음 일정을 확인하세요.`로 결정적으로 대체한다.

data는 `contractVersion`, `tripId`, `tripItemId`, `scheduleVersionId`, `deepLink`만 허용하고 모든 값은 string이다. 세 ID는 canonical lowercase UUID다. key 64, value 512, 전체 key+value 합계 2048 UTF-8 byte 이하다. deep link는 `timingjeju://trips/{tripId}/live?itemId={tripItemId}`와 byte-for-byte 같아야 하며 percent encoding, control character, 다른 query·path를 거부한다.

canonical collapse key는 canonical lowercase UUID `tripId`로 정확히 조립한 `trip:{tripId}:departure`다. 정확한 regex와 UUID roundtrip을 모두 통과해야 하며 uppercase UUID, malformed UUID, 추가 segment를 거부한다. Android `collapse_key`와 APNs `apns-collapse-id`는 이 동일 값을 쓴다. provider TTL은 오직 `min(900, floor(expiresAt - sendAttemptAt))`이며 1..900 정수 초만 허용하고 equality를 포함해 0 이하면 보내지 않는다.

## 동의, 취소와 audit

OS 알림 권한, 서버 출발 알림 설정, **최신 required 위치 동의**가 모두 유효해야 한다. 예약 시점과 발송 직전에 같은 세 신호를 다시 검사한다. 위치 동의는 평가 시각의 최신 effective required `location` 문서 version과 사용자의 ACTIVE consent version이 정확히 같아야 한다. version은 `^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`의 canonical nonblank string이고 상태는 `ACTIVE|WITHDRAWN`만 허용한다. missing/null/blank/wrong type/unknown status는 audit snapshot도 만들지 않고 fail-closed한다. old version, 임의의 newer/non-required version, WITHDRAWN은 모두 부적격이다. 유효한 증거만 documentType, requiredVersion, consentedVersion, consentStatus, evaluatedAt audit snapshot으로 남긴다.

closed `cancelReason`은 정확히 `SCHEDULE_VERSION_REPLACED|ITEM_COMPLETED|ITEM_SKIPPED|TRIP_CANCELLED|USER_OPTED_OUT|PREFERENCE_CHANGED|OS_PERMISSION_REVOKED|LOCATION_CONSENT_INVALID|NO_ACTIVE_PUSH_TARGET|EXPIRED`다. 일정 버전 교체, 항목 완료·건너뜀, 여행 취소, opt-out, preference 변경, OS 권한 철회, 위치 동의 무효, 활성 대상 0건, 만료는 각각 같은 순서의 canonical reason으로 미발송 logical job을 취소한다. 발송 직전 활성 일정·pending item·활성 trip/target·세 동의·양수 TTL을 재검사한다. generation+fencing+lease-owner CAS로 stale worker를 거부한다.

## provider 결과, 재시도와 전달 한계

provider message id 수신만 `ACCEPTED`다. explicit 429/5xx 같은 transient rejection과 request byte가 전혀 보내지지 않았음이 증명된 pre-connect failure만 `RETRYABLE_FAILURE`다. provider 호출이 시작되어 write 여부를 증명할 수 없거나 request byte가 쓰인 뒤 생긴 **post-write/read timeout**, write/read timeout, connection reset, unexpected EOF는 하나의 `post_write_ambiguous` 범주다. 접수 여부를 증명할 수 없으므로 `ACCEPTANCE_UNKNOWN` terminal이며 자동 재시도하지 않는다. permanent token 오류는 `PERMANENT_FAILURE`와 device invalidation이고 payload/config/credential 오류는 device를 무효화하지 않는 permanent failure다.

명시적 retryable attempt만 per-device 최대 3회, Retry-After 우선, 최대 60초 full-jitter exponential backoff로 expiresAt 전에 재시도한다. FCM 접수는 단말 전달 완료가 아니다. `DELIVERED` 상태나 문구를 사용하지 않는다. 앱 재진입 시 푸시 payload가 아니라 `GET /api/v1/trips/{tripId}/live-state`를 다시 조회한다.

## 보안과 후속 Issue readback

FCM registration token은 민감한 기기 식별값이다. API 응답, log, trace, metric과 metric tag에 출력하지 않는다. Firebase service account JSON, private key와 access token은 저장소·fixture에 두지 않고 ADC 또는 secret mount로만 주입한다. data와 예시는 token, 이메일, 위치, 이동 경로, 메모, 프로필, provider credential을 포함하지 않는다.

후속 Issue 본문의 적용 상태를 다시 읽어 `issueReadbackEvidence` 정적 evidence로 고정했다. Issue #113은 `2026-08-28T20:11:58Z`, Issue #114는 `2026-08-28T23:50:45Z`, Issue #115는 `2026-08-26T03:57:27Z`, Issue #116은 `2026-08-26T04:33:51Z` 기준이다. #116 remote/local 본문은 blank-line normalization 후 SHA-256 `de24ed51cd99f944a6a0ed10eba089252e906f8fbb25e2ff0789bc5ea6ebd5da`로 exact 일치한다. #113은 buffer 범위와 최신 위치 동의, #114는 closed payload·provider ambiguity·unexpected EOF, #115는 device-free logical identity와 safetyBuffer version-CAS atomic replacement, #116은 inactive retry target의 새 terminal `SKIPPED` attempt와 duplicate/stale 무수정까지 반영한다.

```bash
python3 -m unittest scripts.tests.test_fcm_departure_notification_contract
python3 scripts/validate_fcm_departure_notification_contract.py
```
