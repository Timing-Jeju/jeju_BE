# 타이밍제주 외부 API 연동 명세 v1.2

## 1. 검증 기준

- 검증일: 2026-07-30
- 외부 API 호출 주체: Spring Boot
- FastAPI MCP 직접 호출: 금지
- API key 저장: 서버 secret manager/env only
- 원문 payload 저장: `external_api_snapshots.raw_payload`에 장애 분석·재처리에 필요한 최소 범위만 저장하고 공개 API로 노출하지 않음

## 2. 결론

| 기능 | 1차 Source | 보조 Source | 계산/저장 |
| --- | --- | --- | --- |
| 관광지/숙소/음식점 기본정보 | TourAPI `KorService2` | 관리자 큐레이션 | Spring -> raw snapshot -> place tables |
| 버스 정류장/노선/경유 순서 | TAGO | 제주 보조 데이터 | Spring -> raw snapshot -> transit tables |
| 실시간 버스 도착 | TAGO | 최근 snapshot fallback | Spring -> raw snapshot -> arrival snapshots |
| 대중교통 경로 후보 | TMAP 대중교통 API 권장 | TAGO graph fallback | Spring facts -> FastAPI 선택/검증 |
| 자동차/렌터카 경로 | TMAP 자동차 경로 API 권장 | 공급자 adapter 교체 가능 | Spring -> raw snapshot -> mobility snapshots |
| 택시 시간/예상 요금 | TMAP 자동차 경로 응답 권장 | 별도 요금 정책 | Spring -> raw snapshot -> mobility snapshots |
| 도보 경로 | TMAP 보행자 경로 API 권장 | PostGIS 직선거리 보수 추정 | Spring -> raw snapshot -> mobility snapshots |
| 날씨 실황/예보 | KMA 단기예보 | 마지막 유효 예보 | Spring -> raw snapshot -> weather tables |
| 위험도/추천/복구 | 외부 API 아님 | - | FastAPI MCP 계산, Spring 저장 |

TMAP은 현재 설계 기본값이다. 실제 발급 계정의 상품/쿼터/약관과 제주 응답 품질을 POC로 통과해야 확정한다. `mobility_route_snapshots.source_provider`를 두어 공급자를 교체할 수 있게 했다.

### 2.1 raw 수집에서 정규화까지

```text
TourAPI · TAGO · KMA · 경로 공급자 raw 응답
  -> data_import_runs 실행/버전/건수 기록
  -> external_api_snapshots 원문·redaction 메타데이터·hash 보존
  -> schema/parser 검증 및 파싱
  -> 장소·교통·날씨 정규화 read model
```

- `data_import_runs`는 `parser_version`, `schema_version`, `sync_mode`, `scope_key`, `request_fingerprint`, `idempotency_key`, parent/retry, 전후 checkpoint와 fetched/inserted/updated/skipped/rejected/deleted/staled 건수를 기록한다. `source_kind`·provider·service·operation·scope는 생성 후 바꿀 수 없고 상태는 `running`, `succeeded`, `failed`, `partial`, `cancelled`를 구분한다. 신규 실행은 `idempotency_enforced`와 `running_scope_enforced`가 항상 `true`다. 멱등 marker의 가장 오래된 행만 partial unique `ON CONFLICT` arbiter이고 선삭제 보호를 받는다. 실행 중 marker는 새 동일 범위 run을 trigger `23505`로 직접 거부하며 `ON CONFLICT` arbiter가 아니다. Spring 내부 생명주기 서비스는 DB unique 결과를 같은 멱등 요청의 재사용과 다른 요청의 실행 중 충돌로 구분하고, `owner_token`과 양수 `fencing_token`이 모두 일치하는 `running` 행만 count·terminal 상태를 쓸 수 있게 한다. owner token은 내부 쓰기 권한으로만 사용하며 응답·로그·오류에 출력하지 않는다.
- `external_api_snapshots`는 provider/service/operation/scope, 요청·payload SHA-256, page, parser version과 `received`/`parsed`/`rejected`/`ignored`/`tombstoned` 상태를 보존한다. 한 run의 모든 snapshot은 정확히 하나의 provider/service/operation/scope를 공유한다. API key, Authorization 헤더, 원문 요청 URL과 PII는 저장하지 않는다.
- TourAPI 정규화 writer는 active registry와 provider/service/operation/snapshot/run/fingerprint 계보를 callback 전에 검증해 invalid command를 선거부한다. callback은 writer transaction에 참여하는 DB write 전용이며 외부 호출·메시지·파일 부수효과를 금지한다. provenance trigger는 최종 방어층으로 계보와 `external_reference_codes`, `tour_places`, `tour_place_sources`, `place_aliases`, `place_details`, `place_detail_items`, `place_images` 중 지정된 실제 UUID 행을 `FOR KEY SHARE`로 잠근다. 대상별 identifier `BEFORE UPDATE`와 `BEFORE DELETE` guard는 동시 변경·삭제와 직렬화해 provenance가 있으면 `23503`으로 거부한다. `place_details`는 `place_id`, 나머지 타입은 `id`를 식별자로 사용하며 후속 normalized type은 allowlist, 정확한 PK branch, identifier mutation guard를 함께 추가한다. fingerprint는 별도 계산하지 않고 SnapshotStoreService가 만든 `SnapshotSaveResult.requestFingerprint`를 상속한다. `snapshot-request-v1`은 provider/service/operation/scope/page와 key 정렬 privacy-canonical metadata를 길이 구분 SHA-256으로 만든다. service key·인증값·원문 URL·사용자 PII는 hash에서 값 차이를 제거한다. 정밀 위치 container는 nested map/list를 재귀 canonicalize하고 내부 민감값을 marker로 치환하며 좌표 값만 저장·로그 없이 hash 입력에서 요청을 구분한다. 한 행의 복수 operation 계보는 보존하고 같은 행/operation/snapshot 중복은 멱등 처리한다.
- 외부 정규화 행은 `parsed`/`tombstoned` 원문과 같은 `import_run_id`·`source_snapshot_id`를 반드시 남긴다. 같은 snapshot/run의 재실행은 정규화 값이 동일한 멱등 upsert만 허용하며, 값이 달라지는 재파싱·보정은 새 snapshot과 그 snapshot의 matching run을 연결한다. 수동·fixture·admin 입력만 snapshot 필수성의 명시적 예외이며 이전·새 행이 모두 예외 성격일 때 편집할 수 있다. 외부 lineage 없는 legacy 정규화 행은 source marker를 예외 값으로 바꿔 우회하거나 내용을 변경할 수 없고, marker가 이미 예외 값이어도 OLD snapshot/run의 실제 `source_kind`·provider가 외부이면 내용 변경과 계보 제거를 수행할 수 없다. retention으로 snapshot 포인터가 NULL이 된 뒤에도 내용과 마지막 run은 새 원문 repair 전까지 불변이다. `data_import_runs`는 origin과 무관한 provenance ledger이므로 16개 정규화 테이블 중 하나라도 run을 참조하면 snapshot 유무나 fixture/admin marker에 관계없이 부모 DELETE를 `23503`으로 거부하고, 미참조 succeeded·failed·fixture·admin run만 삭제할 수 있다. 정확한 16개 FK mapping은 catalog로 감사하며, 8개 `NO ACTION`과 8개 `SET NULL` 모두 FK action보다 먼저 실행되는 `BEFORE DELETE` guard를 통과하므로 정책은 `confdeltype`과 무관하다. 유효한 새 원문과 matching run을 동시에 붙이는 정상 재수집 repair/upsert로 복구할 수 있으며, 기존 non-NULL lineage와 snapshot-backed optional marker 불일치도 소급 감사한다.
- legacy run의 다중 snapshot 범위나 기준 코드·시간표·open/closed 영업시간의 겹치는 유효기간은 식별자를 포함한 사전 audit에서 마이그레이션을 중단한다. 자동 삭제·병합하지 않고 run 분리·행 격리·원천 기준 기간 정리 후 다시 적용한다.
- `data_import_checkpoints`는 provider/service/operation/scope별 마지막 성공 지점만 보존한다. DB의 기대-version CAS 함수로만 전진하며 stale writer는 `40001`, 역행·DELETE·TRUNCATE는 실패한다. 함수는 구현됐고 Spring caller는 후속 importer Issue에서 연결한다.
- `weather_observations`, `weather_forecasts`, `bus_arrival_snapshots`, `mobility_route_snapshots`의 기존 `raw_payload` 컬럼은 호환을 위해 유지하지만 공통 원문 기준은 `external_api_snapshots`이며 신규 적재는 `source_snapshot_id`를 연결한다.
- 수집 내부 테이블은 RLS를 켜고 `anon`·`authenticated` policy/grant를 두지 않는다. 운영 적재의 서버 전용 `service_role`을 브라우저나 FastAPI MCP에 전달하지 않는다.
- `service_role`은 필요한 DML·RPC를 유지하지만 행 trigger를 우회하는 `TRUNCATE`는 기존·향후 public 앱 테이블에서 허용하지 않는다. PostGIS 등 확장 관리 객체는 확장 소유자의 ACL 경계이므로 앱 테이블 검사에서 제외한다. 파괴적 앱 테이블 초기화는 통제된 migration owner 작업으로만 수행한다.

Spring에는 공개 Controller가 없는 import run 생명주기 application port/service와 PostgreSQL adapter가 구현되어 있다. 시작, 멱등 재사용, count 누적, `succeeded`/`partial`/`failed`/`cancelled`, parent/retry 계보와 stale writer 거부까지만 담당한다. 실제 provider 호출·개별 importer·snapshot 저장·checkpoint CAS caller·scheduler·retention job은 아직 구현하지 않았으며 각각의 후속 Issue에서 진행한다.

## 3. TourAPI

### 3.1 공식 서비스

- 공공데이터포털: [한국관광공사 국문 관광정보 서비스 GW](https://www.data.go.kr/data/15101578/openapi.do)
- 최신 서비스 base 안내: `http://apis.data.go.kr/B551011/KorService2`
- 형식: REST, JSON/XML
- 개발계정 기본 트래픽은 공공데이터포털 승인 조건을 따른다.

### 3.2 사용할 operation

한국관광공사의 [2026-01-02 공식 변경 공지](https://www.data.go.kr/bbs/ntc/selectNotice.do?originId=NOTICE_0000000004459)에 따라 2026-01-12 이후 지역·위치·키워드·행사·숙박·동기화 응답은 기존 `areaCode`, `sigunguCode`, `cat1`~`cat3` 대신 법정동·신분류 필드를 사용한다. 신규 importer는 아래 최신 operation과 필드를 기준으로 구현하고 기존 컬럼은 과거 snapshot 재처리 호환에만 사용한다.

| Operation | 용도 | DB |
| --- | --- | --- |
| `ldongCode2` | 법정동 시도·시군구 코드 동기화 | `external_api_snapshots` -> `external_reference_codes` |
| `lclsSystmCode2` | 관광 분류체계 코드 동기화 | `external_api_snapshots` -> `external_reference_codes` |
| `areaBasedList2` | 제주 지역 관광정보 batch 수집 | `external_api_snapshots` -> `tour_places`, `tour_place_sources` |
| `locationBasedList2` | 지도 중심 주변 장소 탐색 보강 | `external_api_snapshots` -> `tour_places`, `tour_place_sources` |
| `searchKeyword2` | 검색어 후보 탐색 | `external_api_snapshots` -> `tour_places`, `tour_place_sources`, `place_aliases` |
| `searchStay2` | 숙박 후보 | `external_api_snapshots` -> `tour_places`, `tour_place_sources` |
| `detailCommon2` | 공통 상세/개요 | `external_api_snapshots` -> `tour_places`, `place_details` |
| `detailIntro2` | 유형별 이용정보 | `external_api_snapshots` -> `place_details` |
| `detailInfo2` | 반복/부가 정보 | `external_api_snapshots` -> `place_detail_items` |
| `detailImage2` | 이미지 목록/저작권 코드 | `external_api_snapshots` -> `place_images` |
| `areaBasedSyncList2` | 변경분 동기화 | `data_import_runs` -> `external_api_snapshots` -> place upsert -> checkpoint CAS |

`locationBasedList2`, `searchKeyword2`, `searchStay2` 후보 보강의 구현·쿼터·transaction 계약은
[TourAPI 후보 보강 importer](../TOURAPI_DISCOVERY_IMPORT.md)를 따른다. 세 operation은 같은 contentid를
한 장소로 병합하지만 operation/request fingerprint/snapshot provenance는 append-only로 각각 보존한다.

정확한 operation suffix와 필수 파라미터는 발급받은 최신 Swagger/활용 매뉴얼로 integration test에서 다시 고정한다.

### 3.3 필드 매핑

| TourAPI | DB | API | 비고 |
| --- | --- | --- | --- |
| `contentid` | `tour_place_sources.external_id`, `tour_places.content_id` | `contentId` | provider/service 범위 원천 식별자; `content_id`는 호환 read model |
| `contenttypeid` | `tour_place_sources.content_type_id`, `tour_places.content_type_id` | 내부 category로 변환 | 원천 유형 코드 |
| `lDongRegnCd`, `lDongSignguCd` | `tour_place_sources.l_dong_regn_cd`, `l_dong_signgu_cd`, `external_reference_codes` | 내부 지역 label | 2026-01-12 이후 법정동 코드 기준 |
| `lclsSystm1`~`lclsSystm3` | `tour_place_sources.lcls_systm1`~`lcls_systm3`, `external_reference_codes` | 내부 category | 2026-01-12 이후 관광 분류체계 기준 |
| legacy `areacode`, `sigungucode`, `cat1`~`cat3` | `tour_place_sources.area_code`, `sigungu_code`, `category_code_1`~`3` | 신규 API에 사용하지 않음 | 2026-01-12 이전 snapshot 재처리 호환만 유지 |
| `title` | `name`, `normalized_name` | `name` | normalized 값은 앱 생성 |
| `addr1`, `addr2` | `address`, `address_detail` | `address` | 원천 |
| `mapx`, `mapy` | `location` | `lng`, `lat` | WGS84 확인 후 PostGIS |
| `firstimage`, `firstimage2` | `tour_places.image_url`, `thumbnail_url` | image URL | 대표 이미지 read model |
| `overview` | `tour_places.overview` | `overview` | HTML 정제 필요 |
| 유형별 이용시간/휴무/주차 | `place_details.*_text` | `operations` | content type별 필드가 다름 |
| 반복 상세의 `serialnum` 등 | `place_detail_items.source_item_key`, `attributes` | 유형별 반복 항목 | item type/key별 멱등 upsert |
| 이미지 ID·명·저작권 | `place_images.source_image_id`, `source_url_key`, `image_name`, copyright/license 컬럼 | 이미지 목록 | URL key는 길이 prefix를 포함한 place/provider/service/URL SHA-256 digest이고 `(place_id, source_url_key)`가 `ON CONFLICT` 기준; 원본 비교로 collision 차단, ID는 추가 unique |
| `modifiedtime` | `tour_place_sources.source_modified_at` | 직접 노출 안 함 | 증분 동기화 및 lifecycle 판단 |

`detailIntro2`의 운영시간 원문 text는 `place_details`에 보존하고 파싱·검수된 반복 영업시간은 요일별 `interval_no`로 저장한다. 자정을 넘는 구간은 익일 첫 구간·휴무와 겹칠 수 없고 정확히 `00:00` 종료는 다음 날을 점유하지 않는다. 장소 행 MVCC 쓰기 펜스가 교차 요일 검사를 직렬화하며 오래된 `REPEATABLE READ` writer는 `40001`로 실패한다. `detailInfo2`와 `detailImage2` 정규화 행은 원문과 같은 snapshot·run lineage를 모두 갖는다.

`detailCommon2`와 `detailIntro2`는 별도 client/parser로 호출한다. 관광지(12), 숙박(32), 음식점(39)의 유형별 원문 필드를 `place_details`의 text 컬럼과 `intro_attributes.detailIntro2`에 함께 보존한다. `overview` 원문은 외부 공개 read model에 직접 노출하지 않고 `intro_attributes.detailCommon2.overviewRaw`에 감사·재처리 경계로 보존한다. `tour_places.overview`에는 script, style, event attribute, 위험 URL과 비허용 요소를 제거한 plain text만 저장한다.

두 operation은 #107의 `tour_api_operation_provenance`를 재사용해 같은 `place_details.place_id`에 operation별 snapshot, import run, request fingerprint를 독립 보존한다. 상세 normalized write는 기존 `tour_place_sources`의 content ID와 content type이 일치할 때만 수행하며 lineage 불일치는 transaction 전체를 rollback한다. 이 batch importer는 자체 lazy TTL을 두지 않는다. snapshot payload freshness와 정리는 #23의 `purge_after` 및 #62 retention 계약을 따른다.

`detailInfo2`는 `numOfRows=100` 고정 계약으로 1 page부터 순서대로 수집한다. 각 provider page는 page-specific `SnapshotSaveCommand`로 먼저 저장하고 gateway가 반환한 동일 raw bytes만 parse한다. true replay는 DB가 반환한 최초 `fetched_at`·현재 status·replay disposition을 사용한다. `received`만 parsed/rejected로 전이하고 이미 `parsed`인 replay는 terminal transition을 생략하며 `rejected` replay는 normalized write 전에 거부한다. 모든 page의 `pageNo`, `numOfRows`, `totalCount`, raw item 수를 검증하고 중간 page는 정확히 100행이어야 한다. provider 실패, total 변경, truncated page, page 간 key 중복이 있으면 complete sweep과 normalized write·누락 lifecycle을 시작하지 않는다. 완전한 전체 응답은 ordered page snapshot ID·request fingerprint·payload hash·raw count manifest로 묶어 한 transaction에 전달한다. 공급자 식별자인 `serialnum`, `subcontentid`, `roomcode`를 우선 `source_item_key`로 사용하고 음식 메뉴처럼 serial number가 없는 유형은 `foodmenu`를 natural key로 사용한다. 전체 응답 배열 순서는 1부터 시작하는 `sequence_no`로 보존하며 각 row의 `(source_sweep_id, source_snapshot_id)`는 실제 포함 sweep page pair를 참조한다. `attributes`는 `{schema, version, fields}` 객체이며 현재 schema는 `tour-api.detailInfo2.{info|course|room|menu}`, version은 `1`이다. schema/version/fields 구조와 JSON escaping을 포함한 canonical serialized UTF-8 전체 크기는 정확히 64 KiB까지 허용한다. HTML text는 실행 요소를 제거한 plain text만, URL은 user info가 없는 HTTP(S) 절대 URL만 fields에 저장하며 원문은 snapshot 경계 밖으로 노출하지 않는다.

`detailImage2`는 같은 page-specific snapshot/replay/complete manifest 규칙을 사용하되 `contentId`, `imageYN=Y`, `subImageYN=Y`, `pageNo`, `numOfRows=100`을 고정한다. `originimgurl`은 user info 없는 HTTPS 절대 URL과 8192 UTF-8 byte 이하만 허용하고 `smallimageurl`, `imgname`, `cpyrhtDivCd`, `copyrightowner`, `license`를 이미지 행에 보존한다. 공급자 `serialnum`이 있으면 URL 변경에도 같은 `source_image_id` 행을 갱신하며, 없으면 DB trigger가 길이 prefix를 포함한 place/provider/service/원문 URL SHA-256 key를 계산하고 원본 비교로 digest collision을 거부한다. complete empty도 image sweep freshness watermark를 기록하고, 누락은 stale 후 다음 더 최신 complete sweep에서 tombstone으로 전이한다.

`areaBasedSyncList2`는 `tour-api/KorService2/areaBasedSyncList2/jeju` checkpoint의 `modifiedTime`을 읽고 provider 요청에는 Asia/Seoul `yyyyMMddHHmmss` 형식의 `modifiedtime`과 `lDongRegnCd=50`, `pageNo`, `numOfRows=100`을 전달한다. 각 page는 삭제 `showflag=0` 응답까지 redacted snapshot으로 먼저 저장하고 저장된 page별 snapshot/run/fingerprint만 normalized row에 연결한다. 중간 page truncated, total 변경, page 안팎 content ID 중복 또는 provider 실패는 normalized row와 checkpoint를 변경하지 않는다. complete empty를 포함한 완전 응답만 정규화 write, run 성공, checkpoint CAS를 한 Spring transaction에서 순서대로 수행한다. source `modifiedtime`이 오래된 변경은 건너뛰고 같은 시각의 같은 값은 lineage까지 불변인 replay로 처리하며, 같은 시각의 다른 값은 전체 rollback한다. 더 최신 `showflag=0`은 첫 적용에서 stale, 다음 더 최신 삭제에서 tombstone으로 전이하고 row와 provenance를 hard delete하지 않는다. stale checkpoint writer의 PostgreSQL `40001`은 retryable `STALE_VERSION`으로 변환하며 최신 normalized row, run, checkpoint를 보존한다. 같은 idempotency key의 재호출은 DB에 저장된 run 상태와 count를 읽으며, 현재 checkpoint의 `last_succeeded_run_id`와 일치하는 `succeeded` run만 저장 count·page count·checkpoint version으로 성공 replay한다. `running`·`failed`·`partial`·`cancelled` 또는 checkpoint 불일치는 provider fetch·snapshot·정규화·checkpoint 부수효과 없이 실패한다. request fingerprint는 호출 중 변하는 checkpoint cursor가 아니라 operation·scope·parser 계약으로 고정한다.

완전 응답에서 기존 item이 처음 누락되면 해당 새 sweep/run을 연결하고 `stale_at`을 기록한다. 같은 content scope advisory lock 안에서 incoming complete sweep의 `fetched_at`을 normalized row와 독립적인 최신 accepted sweep watermark와 비교한다. 최신 complete empty도 watermark와 ordered page manifest를 남기므로 older non-empty/empty는 어떤 row·lifecycle·lineage도 바꾸기 전에 원자적으로 거부된다. 같은 manifest replay는 active key set, page lineage와 payload가 모두 같은 true replay일 때만 불변이고 같은 시각의 다른 manifest는 거부한다. 그 다음 더 최신인 완전 sweep에서도 계속 누락된 경우에만 `tombstoned_at`을 기록한다. 재등장한 item은 같은 natural key row를 갱신해 stale/tombstone을 해제하고 즉시 hard delete하지 않는다.

### 3.4 TourAPI에서 직접 오지 않는 값

| 값 | 실제 Source |
| --- | --- |
| `recommendedStayMinutes` | 관리자 큐레이션 또는 FastAPI/통계 계산 |
| `regionLabel` | 지역코드 앱 매핑 |
| `saved`, `memo`, `tags`, `targetDay` | 사용자 입력 |
| 정규화된 주간 영업시간 | `detailIntro` text 파싱 + 관리자 검수 |
| 일정 가능성/점수/상태 | FastAPI MCP |
| 장소 간 이동시간 | 길찾기 공급자 |

음식점 content type은 활용하되 카페/신규 소규모 매장은 누락될 수 있다. `source_provider = admin_upload` 확장점을 유지한다.

### 3.5 캐시/동기화

아래 항목은 향후 Spring importer가 구현할 운영 정책이다.

- 제주 전체 기본 목록: 1일 1회 증분 동기화.
- 상세/이미지: importer별 TTL을 중복 구현하지 않고 snapshot freshness와 retention 계약을 따른다.
- 삭제/변경: 동기화 목록 결과를 기반으로 stale 처리 후 검증 삭제.
- 검색 요청 중 TourAPI timeout 시 DB cache를 반환하고 `dataFreshness.stale`을 표시한다.

## 4. TAGO 버스

이 문서의 `<percent-encoded-decoded-service-key>`는 환경변수에 percent-encoded 값을 넣으라는 뜻이 아니다. `TOUR_API_API_KEY`, `TAGO_API_KEY`, `KMA_API_KEY`에는 decoded 원문을 주입하고 Spring의 typed credential 경계에서 요청마다 UTF-8 percent-encoding을 정확히 한 번 수행한다. TMAP key는 query가 아닌 인증 header 원문으로 전달한다.

### 4.1 공식 서비스

- [버스정류소정보](https://www.data.go.kr/data/15098534/openapi.do)
- [버스노선정보](https://www.data.go.kr/data/15098529/openapi.do)
- [버스도착정보](https://www.data.go.kr/data/15098530/openapi.do)
- 기존 TAGO API가 아니라 공공데이터포털의 신규 대체 서비스 URL을 사용한다.

### 4.2 정류소

도시코드는 `getCtyCodeList`의 공식 목록에서 런타임에 `제주` 이름을 유일하게 발견하며,
정류소 전체 적재는 `getSttnNoList`에 그 `cityCode`와 고정 `numOfRows=100`, 증가하는
`pageNo`만 전달한다. 두 endpoint는 서버 allowlist에 고정하고 `serviceKey`는 공통 외부 API
credential 경계에서만 주입하므로 importer query·snapshot metadata·로그에는 남기지 않는다.
JSON과 XML 모두 `response/header/resultCode=00` 및 `body/items/item`, `pageNo`,
`numOfRows`, `totalCount` envelope를 동일하게 검증한다. 중간 page 누락, total 변동,
page 간 natural key 중복, 제주 범위 밖 또는 유한하지 않은 좌표가 있으면 정규화 행과
checkpoint는 쓰지 않는다.

```http
GET http://apis.data.go.kr/1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList
  ?serviceKey=<percent-encoded-decoded-service-key>
  &_type=json
  &gpsLati=33.458111
  &gpsLong=126.941516
```

공식 좌표 기반 operation은 반경 500m의 정류소를 반환한다. 서비스가 반환한 결과 외에 앱이 더 넓은 범위를 원하면 DB에 동기화한 정류소를 PostGIS로 검색한다.

| TAGO | DB |
| --- | --- |
| `nodeid` | `bus_stops.node_id` |
| `nodenm` | `bus_stops.node_name` |
| `nodeno` | `bus_stops.node_no` |
| `gpslati`, `gpslong` | `bus_stops.location` |
| 도시코드 | `bus_stops.city_code`, `external_reference_codes` |

정류장 natural key는 `nodeid` 단독이 아니라 `(source_provider, source_service, city_code, node_id)`다. 정규화 행은 원문 snapshot과 같은 import run을 모두 연결한다.

### 4.3 노선과 경유 정류장

노선 importer는 공식 `BusRouteInfoInqireService`의 세 operation만 호출한다. 노선번호
목록은 `getRouteNoList`에 `cityCode`, `routeNo`, 고정 `numOfRows=100`, 증가하는
`pageNo`를 전달하고, 목록에서 얻은 각 `routeId`를 `getRouteInfoIem` 상세와
`getRouteAcctoThrghSttnList` 경유 목록에 전달한다. 인증키는 공통 credential 경계에서만
주입하며 importer query, snapshot metadata와 로그에는 남기지 않는다. JSON/XML 모두
`response/header/resultCode=00`과 `body/items/item` envelope를 검증한다. page 누락,
`totalCount` 변동, route 또는 sequence 중복, 1부터 연속하지 않는 순번, 다른 도시·공급자
정류장과 선행 #35 정류장에 없는 `nodeid`는 정규화 쓰기 전에 거부한다.

`routeId`는 TAGO에서 방향별 운행을 식별하는 안정 키로 사용하고 `route_stops.direction_key`에
같은 값을 저장한다. 따라서 101/201처럼 같은 노선번호의 양방향 `routeId`를 합치지 않으며,
노선은 `(source_provider, source_service, city_code, external_route_id)`, 경유 순서는
`(route_id, direction_key, stop_sequence)` natural key를 유지한다.

```http
GET http://apis.data.go.kr/1613000/BusRouteInfoInqireService/getRouteNoList
  ?serviceKey=<percent-encoded-decoded-service-key>
  &_type=json
  &cityCode=<discovered-city-code>
  &routeNo=101
  &numOfRows=100
  &pageNo=1
```

```http
GET http://apis.data.go.kr/1613000/BusRouteInfoInqireService/getRouteAcctoThrghSttnList
  ?serviceKey=<percent-encoded-decoded-service-key>
  &_type=json
  &cityCode=<discovered-city-code>
  &routeId=<route-id>
  &numOfRows=100
  &pageNo=1
```

```http
GET http://apis.data.go.kr/1613000/BusRouteInfoInqireService/getRouteInfoIem
  ?serviceKey=<percent-encoded-decoded-service-key>
  &_type=json
  &cityCode=<discovered-city-code>
  &routeId=<route-id>
```

| TAGO | DB |
| --- | --- |
| `routeid` | `bus_routes.external_route_id` |
| `routeno` | `bus_routes.route_no` |
| `routetp` | `bus_routes.route_type` |
| 도시코드 | `bus_routes.city_code`, `external_reference_codes` |
| `startnodenm`, `endnodenm` | `direction_name`/route summary 확장 |
| 첫차/막차 | route summary 또는 별도 service window 확장 |
| 평일/토/일 배차간격 | route summary, 위험도 facts |
| 노선별 경유 정류장 목록 | `route_stops` |

도시코드는 하드코딩하지 않고 각 TAGO 서비스의 `도시코드 목록 조회` 원문을 snapshot으로 보존한 뒤 `external_reference_codes`에 적재한다. 노선 natural key도 provider/service/city 범위이며 `route_stops`도 같은 snapshot·run lineage를 남긴다.

### 4.4 실시간 도착

```http
GET http://apis.data.go.kr/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList
  ?serviceKey=<percent-encoded-decoded-service-key>
  &_type=json
  &cityCode=<discovered-city-code>
  &nodeId=<node-id>
```

| TAGO | DB |
| --- | --- |
| `nodeid`, `nodenm` | stop reference/cache |
| `routeid`, `routeno`, `routetp` | route reference/cache |
| `arrprevstationcnt` | `remaining_stops` |
| `vehicletp` | `vehicle_type` |
| `arrtime` seconds | `estimated_arrival_seconds` |
| 수집 시각 | `observed_at` |
| 앱 TTL | `expires_at` |

도착 API의 동시 호출 제한과 쿼터를 고려해 같은 정류소 요청을 합치고 20~30초 single-flight cache를 적용한다.
cache key는 `(provider, service, stop_id, city_code, node_id)` 전체이며 실패한 future는 즉시 제거한다.
정상 응답의 압축 해제된 원문 bytes를 먼저 snapshot으로 저장하고 parser가 같은 bytes를 읽는다.
`resultCode=97`은 원문을 `rejected`로 남기지만 HTTP 429·timeout처럼 응답 bytes가 없는 transport
실패에는 snapshot을 만들지 않는다. 성공한 batch만 같은 transaction에서 snapshot `parsed`,
`bus_arrival_snapshots` append, import run 성공으로 전환한다.

fresh cache가 만료된 뒤 provider가 실패하면 마지막 DB snapshot의 `observed_at`을 기준으로 정확히
120초 이하인 경우에만 stale 결과를 반환한다. stale 응답의 `observed_at`·`expires_at`은 원 관측값을
그대로 유지하고 새 normalized row나 새 성공 run을 만들지 않는다. 120초를 넘거나 공식 empty 응답이면
fallback하지 않는다. `arrtime`은 0~86400초, `arrprevstationcnt`는 0~10000 범위만 허용한다.

### 4.5 TAGO가 보장하지 않는 값

| 값 | 처리 |
| --- | --- |
| 장소 A -> B 완성 대중교통 경로 | TMAP 대중교통 또는 FastAPI graph 탐색 |
| 정확한 모든 정류장 출발 시간표 | 제주 보조 데이터/관리자 적재가 있을 때만 `timetable_entries` 사용 |
| 도보 경로/시간 | TMAP 보행자 또는 보수 추정 |
| 환승 가능성/안전 버퍼 | FastAPI MCP 계산 |
| 버스를 놓쳤을 때 다음 일정 | FastAPI MCP 복구 계산 |

`timetable_entries`는 TAGO에서 항상 채워지는 테이블이 아니다. 확보한 반복 시간표는 source record와 유효기간으로 멱등 upsert한다. UUID FK가 route/stop 존재와 삭제 전파를 보장하고 source scope trigger가 route·stop·route_stop의 `(route_id, direction_key, stop_id, source_provider, city_code)` 조합을 잠금과 함께 검증한다. v1의 경유 누락/provider 불일치 `city_code=NULL`은 보존하되 lineage 없이 내용을 바꿀 수 없다. 유효한 `parsed`/`tombstoned` snapshot과 같은 범위 run을 함께 연결해 정상 scope로 복구하는 재수집은 허용한다. 외부 신규 시간표도 같은 snapshot·run을 연결한다.

## 5. TMAP 경로 API 설계 기본값

- 공식 포털: [SK open API](https://openapi.sk.com/)
- 사용할 상품 후보: TMAP 자동차, 보행자, 대중교통 경로.
- 역할: 경로 polyline, 거리, 예상 시간, 구간별 이동 정보, 가능한 경우 요금.
- TAGO 역할과 중복되지 않는다. TMAP은 경로 후보, TAGO는 정류장 기준 최신 도착을 담당한다.

### 5.1 정규화

모든 공급자 응답은 아래 형태로 변환해 `mobility_route_snapshots`에 저장한다.

```json
{
  "requestHash": "sha256:...",
  "transportMode": "public_transit",
  "origin": {
    "lat": 33.5066,
    "lng": 126.493
  },
  "destination": {
    "lat": 33.458111,
    "lng": 126.941516
  },
  "departureAt": "2026-08-03T09:20:00+09:00",
  "distanceMeters": 47000,
  "durationMinutes": 105,
  "estimatedFare": 3000,
  "sourceProvider": "tmap",
  "sourceOperation": "transit_route",
  "routeSummary": {
    "walkMinutes": 8,
    "waitMinutes": 12,
    "rideMinutes": 80,
    "transferMinutes": 5
  },
  "observedAt": "2026-08-03T09:19:50+09:00",
  "expiresAt": "2026-08-03T09:24:50+09:00"
}
```

DB 행은 위 정규화 값에 더해 같은 원문 범위의 `import_run_id`와 `source_snapshot_id`를 저장한다. 같은 `request_hash`라도 공급자·operation·관측 시각이 다르면 별도 행이며 unique 범위는 `(source_provider, source_operation, request_hash, observed_at)`이다.

### 5.2 POC 통과 조건

- 제주공항 -> 성산일출봉 대중교통 경로가 반환된다.
- 성산일출봉 -> 섭지코지 도보/대중교통 경로가 반환된다.
- 렌터카 시간/거리와 택시 예상요금 필드 가용성을 확인한다.
- 과거/미래 출발시각 지원 범위를 확인한다.
- 응답 polyline 표시 및 저장/재사용 약관을 확인한다.
- 개발/운영 쿼터와 공모전 트래픽 예상치를 비교한다.

POC 실패 시 adapter만 ODsay(대중교통) + 자동차/도보 공급자로 교체한다. DB와 FastAPI 계약은 변경하지 않는다.

## 6. 기상청 단기예보

### 6.1 공식 서비스

- [기상청 단기예보 조회서비스](https://www.data.go.kr/data/15084084/openapi.do)
- Base: `http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0`
- 제공: 초단기실황, 초단기예보, 단기예보, 예보버전.
- 좌표: 위경도가 아니라 기상청 `nx`, `ny` 격자.

### 6.2 operation

| Operation | 목적 | DB |
| --- | --- | --- |
| `getUltraSrtNcst` | 현재 실황 | `external_api_snapshots` -> `weather_observations` |
| `getUltraSrtFcst` | 수시간 이내 예보 | `external_api_snapshots` -> `weather_forecasts` |
| `getVilageFcst` | 여행 일정 단기예보 | `external_api_snapshots` -> `weather_forecasts` |
| `getFcstVersion` | 예보 버전 감사 | `external_api_snapshots`, import metadata |

Example:

```http
GET http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst
  ?ServiceKey=<percent-encoded-decoded-service-key>
  &dataType=JSON
  &pageNo=1
  &numOfRows=1000
  &base_date=20260803
  &base_time=0500
  &nx=60
  &ny=37
```

### 6.2.1 격자와 발표 base 결정 계약

Issue #42의 `KmaGridConverter`는 외부 호출 없이 WGS84 위경도를 KMA DFS 5 km 격자로 변환한다. 공식 격자영역 명세의 Lambert conformal conic 상수(지구반경 6371.00877 km, 표준위도 30°/60°, 기준점 N 38°·E 126°와 X 43·Y 136)를 식에서 직접 유도하고, 투영 좌표에 0.5를 더해 내림하여 가장 가까운 dot grid를 선택한다. 입력은 유한한 위경도와 투영 가능한 위도만 허용하며, 결과가 공식 149 × 253 격자를 벗어나면 외부 API에 잘못된 `nx`/`ny`를 보내기 전에 거부한다. 제주국제공항 등 대표 좌표와 공식 네 모서리를 golden fixture로 고정한다.

`ForecastBaseTimeResolver`는 주입된 `Clock`의 instant를 항상 `Asia/Seoul`로 변환한다. 초단기예보는 매시 30분 base가 45분부터 조회 가능하므로 15분 지연을, 단기예보는 02·05·08·11·14·17·20·23시 base가 각 시각 10분부터 조회 가능하므로 10분 지연을 적용한다. 지연이 끝나지 않은 base는 선택하지 않으며 자정·월·년 경계에서도 직전 날짜의 base를 반환한다. 이 모듈은 HTTP, API key, DB, Controller를 소유하지 않고 후속 #43과 #76 importer가 같은 결정 계약을 재사용한다.

근거는 공공데이터포털의 최신 `기상청41_단기예보 조회서비스_오픈API활용가이드_2607.zip`, 기상청의 [동네예보 데이터 활용안내](https://data.kma.go.kr/community/nuriLovePopup.do), [동네예보 격자영역 정보](https://apihub.kma.go.kr/getAttachFile.do?fileName=%2820240305%29%EB%8F%99%EB%84%A4%EC%98%88%EB%B3%B4+%EA%B2%A9%EC%9E%90%EC%98%81%EC%97%AD+%EC%A0%95%EB%B3%B4.pdf)다.

### 6.3 category 매핑

2026-08-16 기준 공공데이터포털의 2026-07-09 수정본(`기상청41_단기예보 조회서비스_오픈API활용가이드_2607.zip`)과 기상청 API Hub를 교차 확인한다. API Hub는 2026-06-23 12 KST부터 초단기예보 `POP`도 제공한다고 명시하지만, Issue #43의 정규화 계약은 아래 여섯 category만 저장한다. 따라서 `POP`과 초단기 부가 category(`UUU`, `VVV`, `VEC`, `LGT`)는 원문 snapshot에는 남기되 이 importer의 DB 컬럼으로 오분류하지 않는다. 단기예보 전용 `TMP`, `PCP`, `TMN`, `TMX`가 섞인 응답은 거부하며 #76에서 별도 처리한다.

| KMA category | DB |
| --- | --- |
| `TMP`, `T1H` | `temperature_c` |
| `POP` | `precipitation_probability_percent` |
| `PCP`, `RN1` | 정규 시간대는 `precipitation_amount_mm`; 확장 시간대 PCP code는 `precipitation_intensity_code` |
| `PTY` | `precipitation_type` |
| `SKY` | `sky_code` |
| `REH` | `humidity_percent` |
| `WSD` | 정규 시간대는 `wind_speed_mps`; 확장 시간대 WSD code는 `wind_strength_code` |
| `TMN`, `TMX` | min/max temperature |

단기예보 importer는 `numOfRows=1000`으로 `totalCount`가 가리키는 모든 page를 순서대로
수집하고 `getFcstVersion(ftype=SHRT, basedatetime=<base>)`까지 각 HTTP 응답의 압축 해제된 byte를
재직렬화 없이 개별 snapshot으로 먼저 보존한다. ordered manifest에는 snapshot ID, payload hash,
operation/page metadata만 저장하며 provider 원문을 복제하지 않는다. page/version 오류도 terminal
`rejected` audit로 남고 정규화 행과 checkpoint는 원자적으로 바뀌지 않는다.

2024-11-28 기상청 공식 단기예보 서비스 변경 공지 이후 02·05·08·11·14시 발표는 발표 다음
정시부터 발표일+4일 00시 전까지 1시간 간격이고, +4일은 00·03·06·09·12·15·18·21시의
3시간 간격이다. 공식 slot 수는 각각 101·98·95·92·89개다. 17·20·23시 발표는 같은 규칙의
확장일이 발표일+5일이며 공식 slot 수는 각각 110·107·104개다. 각 시간대는
`TMP/POP/PCP/PTY/SKY/REH/WSD`가 완전해야 하며 응답 전체에는 `TMN/TMX`가 모두 있어야 한다.
경계 누락, 중복 category, 비공식 간격은 거부한다. 확장 시간대의 PCP/SNO/WSD 정수는 물리량이
아닌 정성 code이므로 PCP와 WSD는 별도 code 컬럼에 저장하고 mm/mps 컬럼은 null로 둔다.
공식 부가 category와 SNO 원문은 raw snapshot에 보존한다.

강수량은 `강수없음`, `1mm 미만` 같은 문자열일 수 있으므로 parser 버전과 원문 category를 보존한다.

Issue #43 parser의 필수 집계는 다음과 같다.

| Operation | 필수 category | 정규화 단위 |
| --- | --- | --- |
| `getUltraSrtNcst` | `T1H`, `RN1`, `PTY`, `REH`, `WSD`, `VEC` | grid/base당 실황 1행 |
| `getUltraSrtFcst` | `T1H`, `RN1`, `PTY`, `SKY`, `REH`, `WSD` | grid/base/예보시각당 `ultra_short` 1행 |

`RN1`의 `강수없음`은 0 mm, `1mm 미만`은 구간 대표값 0.5 mm로 정규화한다. 숫자 또는 `mm` 접미 숫자는 0 이상 900 미만만 허용하고 KMA 결측 sentinel `+900`/`-900`은 거부한다. 문자열 범주는 공식 `30.0~50.0mm`, `50.0mm 이상`만 보수적으로 하한을 저장하며 임의 범위·하한 문자열은 거부한다. 정확한 원문은 versioned raw snapshot에 보존한다. `PTY`는 실황과 초단기예보 모두 공식 코드 0~7 전체를 허용하고, `SKY`는 공식 코드 1·3·4만 허용한다. 초단기 `fcstTime`은 발표시각보다 엄격히 이후인 정시(`mm=00`)이면서 발표 후 6시간 이내여야 한다. 실황 `VEC`는 0도와 360도를 모두 유효한 경계값으로 허용한다.

실황은 매시 정각 base가 10분 뒤 조회 가능하다고 보고, 초단기예보는 #42의 매시 30분 base/15분 지연 계약을 사용한다. 최신 발표 호출 또는 검증이 실패하면 직전 1시간 base를 정확히 한 번만 시도하고 성공 summary와 checkpoint에 `STALE_WEATHER_DATA`를 기록한다. 두 번째 실패 뒤에는 더 오래된 발표를 탐색하지 않는다.

수집은 operation과 `nx`/`ny` 범위별로 run과 checkpoint를 분리한다. 성공 경로는 raw snapshot을 먼저 저장·`parsed` 전이한 다음 한 transaction에서 정규화 upsert, run 성공, 기대 version checkpoint CAS를 순서대로 수행한다. service key와 전체 query/URL은 snapshot metadata, 예외와 로그에 저장하지 않는다.

### 6.4 저장 여부

날씨는 저장한다.

- 외부 API 반복 호출과 쿼터를 줄인다.
- 어떤 예보로 위험도를 계산했는지 재현한다.
- 예보 갱신 전후 계산 결과를 구분한다.
- UI 조회와 일정 계산이 같은 facts를 사용한다.

예보 자체는 `weather_forecasts`, 일정별 영향은 FastAPI 계산 후 `trip_weather_impacts`에 분리 저장한다.

KMA 원문은 먼저 `external_api_snapshots`에 저장하고, category 파싱을 통과한 날씨 행이 해당 snapshot과 같은 import run을 함께 가리킨다. parser version을 남기므로 해석 규칙이 바뀌어도 같은 원문을 재처리할 수 있다.

원문 저장 구현 계약은 [외부 API snapshot 저장](../EXTERNAL_SNAPSHOT_STORAGE.md)을 따른다. 공급자별 parser는 redaction을 우회해 DB에 직접 쓰지 않는다.

## 7. Source of Truth 표

| API 응답 필드 | Source 유형 | 원천/계산 주체 |
| --- | --- | --- |
| 장소명, 주소, 좌표, 이미지, 개요 | `external` | TourAPI |
| 이용시간/휴무/주차 text | `external` | TourAPI detailIntro |
| 추천 체류시간 | `curated/computed` | 관리자/FastAPI 정책 |
| 관심 여부, 메모, 태그 | `user_input` | 사용자 |
| 정류장 ID/명/좌표 | `external` | TAGO |
| 노선 번호/유형/경유 순서 | `external` | TAGO |
| 버스 도착예정 초/남은 정류장 | `external_snapshot` | TAGO |
| 정확한 대기시간 | `computed` | FastAPI + arrival facts |
| 자동차/도보/대중교통 이동시간 | `external_snapshot` | TMAP adapter |
| 위험도/점수/leave-by | `computed` | FastAPI MCP |
| 날씨 실황/예보 | `external_snapshot` | KMA |
| 날씨 일정 영향 | `computed` | FastAPI MCP |
| 복구안/변경 diff | `computed` | FastAPI MCP |
| 자연어 설명 | `ai_generated` 또는 template | FastAPI/Spring fallback |

## 8. TTL/Fallback

| 데이터 | 정상 TTL | 허용 fallback | fallback 표시 |
| --- | --- | --- | --- |
| TourAPI 목록/상세 | 24시간 | 최근 7일 | `stale=true` |
| 정류장/노선/경유 순서 | 24시간 | 최근 7일 | `stale=true` |
| 실시간 버스 도착 | 20~30초 | 최대 2분 | `STALE_TRANSIT_DATA` |
| 자동차/택시 경로 | 5분 | 최대 30분 | confidence 하향 |
| 도보 경로 | 24시간 | PostGIS 보수 추정 | `ESTIMATED_WALK_TIME` |
| 대중교통 경로 | 5분 | 최근 경로 + TAGO 보정 | `STALE_ROUTE_PLAN` |
| 초단기실황 | 10분 | 최근 30분 | `STALE_WEATHER_DATA` |
| 단기예보 | 같은 발표 base | 이전 발표 1회 | `STALE_WEATHER_DATA` |

`danger` 판단에 필수인 facts가 fallback 한도를 넘으면 계산하지 않고 `EXTERNAL_FACTS_UNAVAILABLE`을 반환한다.

## 9. Adapter 계약

Spring package boundary 예시:

```text
external.tour.TourApiClient
external.transit.TagoStopClient
external.transit.TagoRouteClient
external.transit.TagoArrivalClient
external.mobility.MobilityRouteProvider
external.weather.KmaForecastClient
```

`MobilityRouteProvider` 구현체만 `TmapMobilityRouteProvider`, `OdsayTransitProvider` 등으로 교체한다. Controller나 FastAPI 계약이 공급자 응답 DTO를 직접 참조하면 안 된다.

## 10. 후속 Spring importer Integration Test 체크리스트

- TourAPI `KorService2` 실제 키로 `ldongCode2`, `lclsSystmCode2`, 제주 keyword/location/detail/image를 호출한다.
- `areaBasedList2`, `locationBasedList2`, `searchKeyword2`, `searchStay2`, `areaBasedSyncList2`에서 `lDongRegnCd`, `lDongSignguCd`, `lclsSystm1`~`3`가 원문 snapshot과 장소 source에 손실 없이 보존되는지 확인한다.
- TourAPI 이미지가 없는 장소와 HTML overview를 처리한다.
- TAGO 도시코드 목록에서 제주 코드와 지원 여부를 런타임 확인한다.
- TAGO 제주 정류장, 노선, 경유 정류장, 도착 API의 ID가 서로 join된다.
- TAGO 빈 도착 결과와 error code 97/쿼터 초과를 처리한다.
- TMAP 세 모드 POC와 약관/쿼터를 확인한다.
- KMA 위경도 -> `nx`, `ny` 변환 golden test를 만든다.
- KMA base time 이전 발표 fallback과 category 문자열 parser를 검증한다.
- 모든 adapter가 timeout, retry, circuit breaker, metric을 가진다.
- raw snapshot이 `parsed`/`tombstoned`가 된 뒤에만 정규화 upsert하고 모든 외부 행의 snapshot과 import run 범위가 같은지 확인한다.
- 부분 실패·재시도·동시 실행에서 기대 version이 낡은 checkpoint writer가 `40001`로 실패하고 마지막 성공 run이 단조롭게 전진하는지 확인한다.
