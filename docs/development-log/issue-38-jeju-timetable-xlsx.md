# Issue #38 제주 101·201 시간표 XLSX importer 개발 기록

## 범위와 기준

- 기준: `origin/develop` `6cfa98f`
- 브랜치: `feat/38-jeju-timetable-xlsx-reintegrate`
- 공개 API/OpenAPI 변경 없음. Spring application port와 JDBC adapter 경계만 추가한다.
- 공식 bytes를 fixture나 DB에 보존하지 않는다. exact bytes SHA-256과 closed canonical parsed manifest만 lineage에 둔다.
- FastAPI/Spring AI는 변경하지 않는다.

## Red

운영 코드 전에 parser/security/application/migration 테스트를 추가했다.

```text
./gradlew --no-daemon test --tests '*JejuTimetableXlsxParserTest' --tests '*JejuTimetableImportServiceTest' --tests '*TimetableMigrationContractTest'
> Task :compileTestJava FAILED
cannot find symbol: JejuTimetableXlsxParser, JejuTimetableImportService, TimetableImportStore
39 errors
```

Red 테스트 단독 커밋은 pre-commit hook이 같은 실패 compile을 실행해 차단했다. Hook 우회는 하지 않았으며 저장소 TDD 가이드에 따라 명령·테스트명·핵심 실패를 이 문서에 보존했다.

Apache POI는 test helper만이 아니라 운영 OOXML parser가 직접 사용하는 의존성이므로 `poi-ooxml:5.4.1`을 `implementation`으로 고정했다. 테스트는 공식 bytes 복사본이 아니라 row 2-6 metadata, row 7 header, 양방향 sheet, shared-string time, merged anchor, marker와 bogus dimension을 코드로 생성한다.

## Green/Refactor

- exact allowlist mapping은 `operator-mapping-v1`의 `gscheduleId + sheet + header`를 기존 `TAGO/39` route/direction/stop UUID에 연결한다. fuzzy match는 없다.
- 시간은 `H:mm/HH:mm`과 제한된 괄호·개행 주석만 허용하고 24:00/25:xx, numeric/formula를 닫힌 실패로 처리한다.
- ZIP entry/path/macro/external-link/expanded-size/file/row/sheet 상한과 filesystem containment, regular-file, no-follow를 적용한다. worksheet dimension metadata는 신뢰하지 않는다.
- dry-run은 catalog와 기존 version/hash까지 읽기 검증하되 write는 0회다. production은 run + JSON manifest snapshot + timetable batch를 하나의 `@Transactional` JDBC commit으로 전달한다.
- `20260915000000` additive migration은 timetable provenance와 route reference scope를 분리하고 legacy 값을 backfill한다. legacy 이상 행은 삭제·자동 수정하지 않고 `NOT VALID`로 보존한다.
- 201의 unnamed physical column은 추론하지 않고 안정 코드와 sheet/row/column omission을 남긴다. 공식 시작 8~13행·종료 65~70행 merge만 허용하며 covered cell을 복제하지 않고 allowlisted annotation override 하나만 발행한다.
- Draft 2020-12 mapping schema와 runtime constructor가 101/201 schedule-route-date, exact sheet, B3/B5 digest, ordered columns를 독립적으로 잠근다. 기본 비활성 internal `ApplicationRunner`가 schema 검증 mapping과 안전하게 읽은 XLSX만 dry-run 기본값으로 실행한다.
- canonical source는 제주특별자치도/`JEJU_PROVINCE`, dataset `3043887`, UDDI `uddi:3e6924f3-f090-495a-bc59-ad7e159d78f2`, 이용허락 `제한 없음`(2026-09-04 확인)이다.

## 검증과 의도적 제외

실행 결과:

```text
./gradlew --no-daemon spotlessApply test --tests '*Timetable*' architectureTest
BUILD SUCCESSFUL
```

집중 unit/source/architecture 테스트만 실행했다. 현재 filesystem이 `SecureDirectoryStream`을 지원하지 않아 regular-file 성공 경로 1건은 assumption skip됐고, 미지원 filesystem fail-closed 경로는 통과했다. 사용자 지시에 따라 실제 DB migration 적용, PostgreSQL/Testcontainers, Docker, live Supabase/provider, 전체 `clean check`와 heavy quality gate는 실행하지 않는다. 따라서 결과는 source-ready이며 `READY_FOR_REVIEW`가 아니다.
