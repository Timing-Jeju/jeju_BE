# Definition of Done

다음 항목이 모두 충족되어야 개발 완료입니다.

- GitHub Issue와 Acceptance Criteria가 존재합니다.
- 최신 `develop`에서 규칙에 맞는 작업 브랜치를 만들었습니다.
- Red 실패, Green 통과, Refactor 후 전체 테스트 증거가 있습니다.
- 단위·슬라이스·통합·Architecture 테스트와 커버리지 검증이 통과했습니다.
- 포맷, 컴파일, `bootJar` 빌드가 통과했습니다.
- Spring 공개 API 변경 시 Swagger UI 통합 테스트와 `openApiDocs` 생성이 통과했습니다.
- 백엔드 저장소 구조 테스트와 Spring 전용 품질 게이트가 통과했습니다.
- FastAPI MCP 계약 변경이 포함되면 `jeju_AI` 저장소의 대응 Issue·PR과 호환 순서를 연결했습니다.
- Docker 이미지 빌드, Compose 실행, Health Check, 리소스 정리가 성공했습니다.
- 비밀정보와 불필요한 파일이 diff에 없습니다.
- 최신 HEAD의 품질 게이트 성공 기록이 있습니다.
- PR 전 Reviewer의 필수 수정사항이 0개이고 최신 HEAD가 APPROVED입니다.
- PR은 일반 작업이면 `develop`, Release면 `main`을 base로 합니다.
- 개발 문서와 한국어 Obsidian 개발 일지가 갱신되었습니다.

## FCM 출발 알림 추가 완료 조건

Issue #112를 상속하는 알림 구현은 Spring이 token·예약·취소·발송·제한 재시도를 단독 소유하고 FastAPI가 관여하지 않는지를 검증해야 합니다. 앱 종료용 메시지는 `notification + data`이며 OS 알림 권한, 서버 출발 알림 설정, 최신 required 위치 동의를 예약 시점과 발송 직전에 다시 확인하고, 일정 버전 취소·deduplication·generation fencing·짧은 TTL을 통합 테스트로 증명해야 합니다.

복수 기기는 device가 없는 logical job 한 건, claim commit 후 preparation eligibility 재검사로 저장한 닫힌 `push_delivery_targets` snapshot/current state와 `(jobId, pushDeviceId, attemptNo)`별 단일 delivery attempt row로 검증해야 합니다. attempt status는 `RESERVED → CALL_STARTED → terminal` same-row CAS이고 terminal 뒤 immutable해야 합니다. expired `LEASED` reclaim은 같은 state/generation/target/attempt identity를 보존하고 owner/lease/fencing만 새로 발급하며 old fence completion을 거부해야 합니다. reclaim 뒤 `RESERVED`/`CALL_STARTED` marker별 same-row recovery와 claim↔preparation·post-snapshot·호출 직전 race를 검증해야 합니다. 세 번째·TTL 만료 transient attempt도 immutable 보존하면서 job `DEAD`를 원자 반영해야 합니다. `safetyBufferMinutes` 변경은 preference CAS부터 old generation/job 취소와 재계산까지 원자여야 합니다. FCM 접수는 단말 전달 완료가 아니다. explicit rejection/pre-connect만 재시도하고 post-write/read timeout, connection reset, unexpected EOF는 terminal `ACCEPTANCE_UNKNOWN`이어야 합니다. 앱 재진입 시 `live-state`를 다시 조회합니다. registration token은 API 응답·로그·trace·metric에 없어야 하며 Firebase credential은 ADC 또는 secret mount로만 주입합니다. #93과 정정된 #113~#116 구현이 준비되지 않으면 production default-off와 fail-closed여야 합니다.

generation/expectedGeneration의 single generation naming만 허용해야 합니다. preparation eligible target 0건은 empty snapshot + `CANCELLED/NO_ACTIVE_PUSH_TARGET` + attempt/provider 0회를 같은 transaction에서 검증합니다. target의 `UNATTEMPTED|RETRYABLE → RESERVED → IN_FLIGHT → terminal/retryable`와 `UNATTEMPTED|RETRYABLE → SKIPPED` closed transition을 증명해야 합니다. inactive retry target은 provider 0회, attemptNo+1 exact terminal `SKIPPED` row·target·job aggregation same CAS transaction이어야 하며 duplicate key/stale CAS는 무수정이어야 합니다. completion은 existing exact `CALL_STARTED` attempt와 `IN_FLIGHT` target만 허용하고 absent/reserved/terminal/wrong-marker/second completion을 거부해야 합니다.
