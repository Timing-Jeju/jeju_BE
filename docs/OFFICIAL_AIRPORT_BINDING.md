# 공식 공항 canonical binding (#271)

대응 AI 구현: https://github.com/Timing-Jeju/jeju_AI/issues/22

기존 FE API의 UUID와 `tour_places` FK를 유지한다. 실제 KAC 공항을 TourAPI 매장 또는
가짜 content ID로 대체하지 않는다. 기존 TourAPI 장소의 `tourapi.place:<숫자>`는 그대로다.

## 승인 출처와 식별자

- 공식 dataset: https://www.data.go.kr/data/15002851/fileData.do
- 제공기관: 한국공항공사, 자료 기준일 2025-08-01
- 원본 SHA-256: `2d16655b866cda93790d44cc8460f42eb7ba7c2dde3be72adfef2cf8719a39a6`
- 원본 제주 행의 공항명은 `제주`, 주소는 `제주 제주시 공항로 2`, 위도 33.511111, 경도 126.492778이다.
- MCP 승인 source ID는 `kac.airport`, fact ID는 `kac.airport:CJU`이다. CJU는 canonical 공항 코드 매핑이며 원본의 별도 IATA 열을 주장하지 않는다.
- canonical 표시 이름 `제주국제공항`은 원본 행과 승인된 공항 코드 매핑으로 연결한다.
- 이 좌표는 공항 **대표점**이다. 검증된 터미널 입구나 탑승 위치가 아니다.

## 등록 계약

새 migration `20260919060000`는 binding 테이블과 승인 조회 view만 추가하며 실제 원본이나 장소를 자동 삽입하지 않는다.
공식 원본의 별도 승인·보존 및 checksum 검증 뒤 다음 계보로 등록한다.

1. `data_import_runs`: `source_kind=admin_upload`, `source_name=kac.airport`,
   `source_operation=15002851`, `source_provider=한국공항공사`, `source_service=15002851`, 성공 상태와 실제 finished_at.
   metadata의 `raw_sha256`, `source_date`는 binding과 일치해야 한다.
2. `external_api_snapshots`: 같은 import run/provider/service/operation의 실제 원본을 보존하고
   parsed 상태와 `payload_hash=raw_sha256`를 검증한다. 시험 fixture와 운영 원본을 혼동하지 않는다.
3. `tour_places`: 기존 canonical UUID를 보존하고 `content_id=NULL`,
   `source_provider=한국공항공사`, `source_service=15002851`, 동일 import run/source_snapshot_id 및 실제 공공 좌표를 사용한다.
4. `approved_airport_place_bindings`: source `kac.airport`, external ID `CJU`,
   source record `제주`, dataset `15002851`, 연결된 snapshot fetched_at과 동일한 실제 수집 시각과 그 시각 기준 최대 30일 검토 만료 시각,
   실제 source date/checksum/import run 및 원본 좌표를 연결한다.
5. BE `SCHEDULE_GENERATION_APPROVED_AIRPORT_PLACE_ID`에 그 canonical UUID를 지정한다.

공항 이름이나 설정 UUID만으로는 승인되지 않는다. source 계보, 좌표, 성공 import,
비삭제·비만료 상태가 동시에 일치할 때만 resolver가 반환한다. 중복 source/external ID와
중복 canonical binding은 DB 제약으로 거부한다. 미승인 source/fact ID는 Java에서 거부한다.

## 입구와 배포 경계

`GenerationEntranceEvidence`는 공항 대표점을 기존 `place-point:` 처리와 같은 **대표점 fallback**으로 구분한다.
별도 승인된 `travel.place-entrance-map` 근거 없는 임의 입구 ID를 허용하지 않는다.
반환된 대표점의 provisional/risk 처리와 충분한 경로 근거, 정확히 세 후보 조건은 유지한다.

이 BE 변경만으로 AWS MCP에 공항이 생기지 않는다. 대응 AI source/정규화/실제 게시가 필요하다.
이번 작업은 BE 운영 배포나 FE UI 변경을 수행하지 않는다. 전체 생성·조회·적용 성공은 실제 검증 결과와 구분한다.
