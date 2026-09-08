# 사용자 현재·간접 위치 무수집 정책 v2 — Issue #220

현행 제품 정책은 [v2 기계 판독 계약](contract.json)이다. Issue #73의
[위치 보관 v1](../location-retention/contract.md)은 과거 설계·적용 이력으로만 보존하며
새로운 수신·저장·추천·알림의 승인 근거로 사용할 수 없다. 기존 v1 JSON/fixture와 적용된
migration은 변경하지 않는다. 본 문서는 법률기관의 승인값이 아니다.

## 책임과 출처

Spring과 private MCP는 사용자 현재 GPS 및 GPS에서 파생한 지역, 최근접 장소·정류장,
격자, 경로, 정확도, 속도, 방향, digest/hash/cache key/fingerprint를 수신·저장하지 않는다.
로그·access log·APM·metric label·trace·Problem Details·raw fixture도 같은 금지 경계다.
위치를 받은 뒤 즉시 지우거나 hash로 바꾸는 구현은 무수집이 아니다.

모바일 GPS는 권한 처리·지도 표시·거리·최근접·도착·이탈 판단의 로컬 동작에만 사용한다.
`placeId`도 GPS 최근접 결과를 자동 전송하면 금지된다. 서버에 보낼 수 있는 참조는 사용자가
직접 고른 공개 지역/장소/정류장 또는 owner 검증된 기존 계획 항목/구간이다.
계획 선택 UI와 로컬 GPS 계산 데이터의 흐름을 분리하고 네트워크 capture 테스트로 확인한다.

공개 TourAPI 장소, TAGO 정류장, KMA grid 좌표는 사용자 위치와 구분해 유지한다.
공개 자료 승인·원천과 versioned projection의 계보를 서버가 검증해야 하며, 사용자가 보낸
`source=public` 또는 `origin=explicit_selection` 같은 자기 선언으로 허용하지 않는다.
클라이언트가 임의로 붙인 출처 field는 공개 API에 추가하지 않는다.

`validate_zero_location_policy.py`는 코드/계약 감사에서 추출한 **field descriptor**의
허용·금지 매트릭스를 실행하는 도구다. HTTP body validator나 middleware가 아니고 타입,
UUID, owner 인증, source 승인 또는 실제 provenance를 대신 검증하지 않는다. `fields`와
`origin`은 API wire 입력이 아니다. 런타임 차단과 실제 정보 흐름 검증은 후속 이슈가 소유한다.

## 영향 표면과 변경 계약

| 표면 | 새 입력 또는 처리 경계 | 제거할 legacy 경로 | 후속 |
| --- | --- | --- | --- |
| Places | query/category/명시 regionCode/size/cursor | lat/lng/radiusMeters, 현재 위치 nearby sort와 cursor hash | #221 |
| Weather | regionCode/placeId/tripItemId 중 정확히 하나와 dateTime | GPS lat/lng 입력 및 GPS에서 고른 region/place | #222 |
| 계획 anchor | 공개 place/stop 또는 저장 일정 참조의 출처 검증 | `facts.location` 임의 좌표 fallback | #225 |
| execution | eventType, 계획 item/leg, occurredAt, clientEventId | currentLocation/currentPlaceId/locationSupplied | #59, #223 |
| live-state | 활성 일정 참조·시각·수동 진행 | 현재 GPS와 GPS 파생 장소·격자 | #58, #224 |
| spare-time/recovery | 계획 참조와 시간창 | 현재 위치와 자동 최근접 참조 | #57, #60, #98, #100, #102, #103 |
| MCP | 계획 참조·공개 facts·시간, Spring 소유권 확인 후 전달 | GPS·원문·사용자 JWT·서버 비밀정보 | #52, #224, jeju_AI 후속 |
| command/MCP hash/cache | 위치가 없는 검증된 입력 또는 승인 공개 source만 | 위치 파생 digest·GRID_100M·geohash | #223, #224 |
| observability | 상태·count·latency·독립 생성 trace/run ID | 장소/지역/GPS/사용자 원문 및 위치 파생 지문 | #223, #224 |
| FCM | active device + OS GRANTED + server opt-in | 위치 동의 eligibility·LOCATION_CONSENT_INVALID | #112, #115, #116 |

날씨 selector 누락/복수는 `400 INVALID_WEATHER_SELECTOR`다. `tripItemId`는 인증된 owner만
조회하며 타 사용자 참조는 404로 은닉한다. 서버가 공개 지리정보에서 grid를 내부 결정한다.
반환 오류에 원천 좌표·내부 grid·provider 메시지를 반사하지 않는다.

개별 REST body/query의 타입·required·enum·오류 전체는 각각의 후속 endpoint 계약이 고정한다.
이 정책의 field descriptor는 해당 endpoint DTO/OpenAPI에 대한 추가 허용 필드가 아니다.
예를 들어 빈 Places query는 endpoint 기본 목록 정책으로 허용할 수 있지만 무수집 감사 fixture의
빈 field 목록으로 모든 검사 표면을 통과시킬 수는 없다.

## 무수집 전환과 legacy 데이터

선행 #217 반영 뒤 API 수정 순서는 **#221 → #222 → #225 → #223 → #224**다.
DB 단계는 새 쓰기 차단 → 계획 공개 anchor 분류 → 직접·간접 위치와 파생 hash purge →
zero-residue 검사 → 명시 schema drop → 불필요 cleanup runtime 제거다.

- 알려진 공개 source와 저장된 계획 owner lineage를 보존 대상으로 먼저 분류한다.
- provenance가 불명확한 활성 일정/후보/run은 삭제하지 않고 중단한다.
- 위치 문자열만 NULL 처리하고 그 위치에서 계산한 hash를 보존하는 방식은 금지한다.
- 기존 migration을 수정하지 않는다. forward migration의 drop은 CASCADE 없이 컬럼,
  인덱스, constraint, trigger, function, grant를 의존 순서대로 명시한다.
- RLS만으로 무수집을 주장하지 않으며 service_role도 새 위치 쓰기 금지를 우회할 수 없다.
- 검증 출력은 row count와 object name만 사용한다. 실제 값이나 row body는 출력하지 않는다.
- 논리 삭제로 backup/PITR의 물리 삭제를 완료했다고 주장하지 않는다. 실제 보존 만료는
  환경별 보존 설정·계약을 확인해야 한다. backup 복원은 격리 환경에서 같은 guard/purge와
  zero-residue 검증을 마친 뒤에만 트래픽을 받는다.
- schema 전환 후 위치 수집 기능이 있는 구버전으로 rollback하지 않는다. 호환되는 무수집
  release를 복구하거나 해당 기능을 비활성화한다.

## 저장 정책과 출시 경계

공개 노드 간 이동이라는 이유로 TMAP 수치 저장을 자동 허용하지 않는다. source별 보존 계약과
#216의 field별 저장 근거는 별도 선행 조건이다. TAGO 실시간, KMA 등 source 정렬도 필요하다.
Spring facts 공급 소유권은 별도 ADR에서 정렬하며 이 위치 정책은 ADR-0052의 수집 소유권을
암묵적으로 바꾸지 않는다. AI 수집기는 대체 공급 및 동등성 검증 전에 삭제하지 않는다.

Issue #168은 모바일 OS GPS·고지의 별도 검토다. 완료되더라도 Spring 위치 수집을 켤 수 없다.
법적 신고·승인 완료를 주장하지 않는다. Notion/Figma는 실제 readback이 없어 `not-linked`다.

## 검증·완료 기준

계약·fixture는 fingerprint로 재귀적으로 고정하고 unknown/alias/nested 위치 field,
명시 선택과 GPS 파생 참조, 공개 좌표 출처, hash/로그 경계, 날씨 selector XOR를 검사한다.
모든 표면에 허용·거부 fixture가 있어야 하고 과거 v1 JSON의 canonical digest를 보존한다.
Shell·PowerShell 품질 게이트가 v1 이력 검사와 v2 현행 검사 모두를 실행한다.

이 문서 변경은 공개 API에서 위치 입력이 실제로 0개라는 증거가 아니다.
`runtimeRemovalVerified=false`, 모든 표면 `runtimeStatus=pending`이다. 후속 구현이 실제
request schema·요청·로그·DB·hash 경로에서 무수집을 증명할 때만 전환 완료로 판정한다.
본 이슈는 Controller/Service/Repository/DB migration/실제 purge/배포를 포함하지 않는다.
