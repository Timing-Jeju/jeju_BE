# 제주 공식 형식 시간표 synthetic fixture

Issue #38 테스트가 코드로 생성하는 재현 가능한 synthetic OOXML 구조의 설명이다. 제주특별자치도 공식 파일 bytes를 복사하거나 재배포하지 않으며 현재 운행을 보장하지 않는다.

- 제공기관/코드: 제주특별자치도 / `JEJU_PROVINCE`
- 공공데이터포털 dataset PK: `3043887`
- UDDI: `uddi:3e6924f3-f090-495a-bc59-ad7e159d78f2`
- 이용허락 근거: 제한 없음, 2026-09-04 확인
- 101: `gscheduleId=405001`, 내부 시행일 2024-08-15
- 201: `gscheduleId=405009`, 내부 시행일 2024-08-01
- 구조: metadata row 2-6, header row 7, exact direction sheet 2개, shared-string 시간, `X/○/blank`, 201 시작 8~13행·종료 65~70행 병합 anchor와 worksheet dimension `A1`

운영자는 `operator-mapping-v1` schema로 각 `gscheduleId + sheet + exact ordered column`을 기존 `TAGO/39` route/direction/stop UUID에 연결해야 한다. 201의 의미를 확정할 수 없는 unnamed physical column은 `IGNORE_UNRESOLVED`로만 두고 `UNRESOLVED_OFFICIAL_COLUMN_OMITTED` 건수를 dry-run·manifest에 남긴다. 허용된 `성산일출봉 출발/종료`, `고성 경유`만 exact stop UUID의 단일 event로 override하며 merge anchor를 다른 stop에 복제하지 않는다. fuzzy match와 자동 보정은 금지한다. 00:00..23:59만 허용하며 24:00 이후는 별도 day-offset migration 전까지 거부한다.

importer는 public API가 아니라 기본 비활성 `ApplicationRunner`다. 실행 시 `timing-jeju.timetable-import.enabled=true`, 절대 `root`, root 아래 `file`, 절대 `mapping-file`, `idempotency-key`를 명시하고, 실제 쓰기는 `dry-run=false`를 별도로 지정한다. 기본값은 dry-run이다.
