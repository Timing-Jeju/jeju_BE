# TourAPI 제주 장소 기본 목록 적재

Issue #26의 importer는 Spring 내부 application command이며 공개 HTTP Controller를 추가하지 않는다.

## 고정 원천 계약

- provider/service/operation/scope: `tour-api` / `KorService2` / `areaBasedList2` / `jeju`
- 요청: `lDongRegnCd=50`, `numOfRows=100`, JSON, 1부터 시작하는 page 번호
- 필수 행 필드: `contentid`, `contenttypeid`, HTML이 아닌 title, WGS84 제주 좌표
- 제주 좌표 경계: 경도 `126.0..127.0`, 위도 `33.0..34.0`을 양끝 포함으로 허용한다.
- page 번호, 응답 `numOfRows=100`, `totalCount`, 실제 전체 item 수가 일치하지 않으면 normalized write 전에 run을 실패시킨다.

## 저장과 계보

- `tour_place_sources(source_provider, source_service, external_id)`와 `tour_places.content_id`를 기존 natural key로 재사용한다.
- 신규 migration이나 Flyway 없이 `supabase/migrations`의 canonical `tour_places`, `tour_place_sources`에 upsert한다.
- 각 page의 `external_api_snapshots`가 만든 request fingerprint와 snapshot/run ID를 normalized row에 기록한다.
- #107의 `TourApiProvenanceWriter`로 장소와 source write 및 `tour_api_operation_provenance` 두 행을 하나의 transaction에 묶는다.
- 동일 snapshot replay는 row와 timestamp를 바꾸지 않는다. 새 snapshot은 같은 장소/source UUID를 유지하면서 lineage와 변경값을 함께 갱신한다.

## partial 정책과 보안

좌표 오류, 필수값 누락, blank·HTML title 같은 행 오류는 raw 행을 별도 로그에 남기지 않고 reason별 count만 summary에 포함한다. 유효 행은 한 번의 repository batch transaction으로 저장하고 run은 `partial`과 `IMPORT_PARSE_REJECTED`로 종료한다. Provider detail, raw query, credential과 raw payload는 application 로그나 결과 summary에 노출하지 않는다.

새 환경변수와 공개 API 변경은 없다. 실제 provider key가 없는 기본 검증은 deterministic fixture와 PostgreSQL Testcontainers로 수행한다.
