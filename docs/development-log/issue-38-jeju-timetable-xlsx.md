# Issue #38 제주 101·201 시간표 XLSX importer 개발 기록

## 범위와 기준

- 최신 통합 기준: `9e0fa607e6d152a1965c7de8bc4ff567db7756c4` (#51 source-approved stack)
- 검증된 #38 소스 기준: `ece8764c469dfa5fc2a8b99c17e6b45e3d7cb4f4`
- 브랜치: `fix/38-jeju-timetable-current-stack`
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

최신 스택 재통합에서는 운영 변경 전에 `scripts.tests.test_issue38_current_stack_contract`를 추가했다. migration·importer·mapping fixture가 없고 기존 #38의 Docker init slot `045`가 #78 profile-image와 충돌하는 상태를 `2 failures, 1 error`로 재현했다. Red 커밋은 `3583b6e9`이며 최신 계약은 profile-image `045` 다음 시간표 `046`을 요구한다.

Apache POI는 test helper만이 아니라 운영 OOXML parser가 직접 사용하는 의존성이므로 `poi-ooxml:5.4.1`을 `implementation`으로 고정했다. 테스트는 공식 bytes 복사본이 아니라 row 2-6 metadata, row 7 header, 양방향 sheet, shared-string time, merged anchor, marker와 bogus dimension을 코드로 생성한다.

## Green/Refactor

- exact allowlist mapping은 `operator-mapping-v1`의 `gscheduleId + sheet + header`를 기존 `TAGO/39` route/direction/stop UUID에 연결한다. fuzzy match는 없다.
- 시간은 `H:mm/HH:mm`과 제한된 괄호·개행 주석만 허용하고 24:00/25:xx, numeric/formula를 닫힌 실패로 처리한다.
- ZIP entry/path/macro/external-link/expanded-size/file/row/sheet 상한과 filesystem containment, regular-file, no-follow를 적용한다. worksheet dimension metadata는 신뢰하지 않는다.
- dry-run은 catalog와 기존 version/hash까지 읽기 검증하되 write는 0회다. production은 run + JSON manifest snapshot + timetable batch를 하나의 `@Transactional` JDBC commit으로 전달한다.
- `20260915000000` additive migration은 timetable provenance와 route reference scope를 분리하고 legacy 값을 backfill한다. legacy 이상 행은 삭제·자동 수정하지 않고 `NOT VALID`로 보존한다. migration timestamp는 바꾸지 않고 Docker init mount만 최신 스택의 다음 순번 `046`을 사용한다.
- 201의 unnamed physical column은 추론하지 않고 안정 코드와 sheet/row/column omission을 남긴다. 공식 시작 8~13행·종료 65~70행 merge만 허용하며 covered cell을 복제하지 않고 allowlisted annotation override 하나만 발행한다.
- Draft 2020-12 mapping schema와 runtime constructor가 101/201 schedule-route-date, exact sheet, B3/B5 digest, ordered columns를 독립적으로 잠근다. 기본 비활성 internal `ApplicationRunner`가 schema 검증 mapping과 안전하게 읽은 XLSX만 dry-run 기본값으로 실행한다.
- canonical source는 제주특별자치도/`JEJU_PROVINCE`, dataset `3043887`, UDDI `uddi:3e6924f3-f090-495a-bc59-ad7e159d78f2`, 이용허락 `제한 없음`(2026-09-04 확인)이다.

## 검증과 의도적 제외

실행 결과:

```text
./gradlew --no-daemon spotlessApply test --tests '*Timetable*' architectureTest
BUILD SUCCESSFUL

latest stack focused unit: BUILD SUCCESSFUL
commit hook unit: 1,319 tests, failures 0, errors 0, skipped 7
architecture: 45 tests, failures 0
slot/profile/push regression Python: 21 tests, OK
```

집중 unit/source/architecture 테스트만 실행했다. 현재 filesystem이 `SecureDirectoryStream`을 지원하지 않아 regular-file 성공 경로 1건은 assumption skip됐고, 미지원 filesystem fail-closed 경로는 통과했다. 사용자 지시에 따라 실제 DB migration 적용, PostgreSQL/Testcontainers, Docker, live Supabase/provider, 전체 `clean check`와 heavy quality gate는 실행하지 않는다. 따라서 결과는 source-ready이며 `READY_FOR_REVIEW`가 아니다.

## 현재 운영 입력 차단과 후속 QA

- 저장소에는 제주 공식 XLSX bytes와 검증된 실제 `TAGO/39` route/direction/stop UUID mapping이 없다. 코드 생성 synthetic POI workbook은 parser 계약 증거일 뿐 실제 운행 데이터가 아니다.
- 실제 값을 추정·생성·커밋하지 않는다. 공식 파일 사용 권한과 operator mapping을 확보한 뒤 별도 운영 절차에서 dry-run report를 먼저 검증해야 한다.
- 향후 승인을 받은 뒤 disposable PostgreSQL/Testcontainers에서 migration·legacy audit·원자 write를 확인하고, Docker fresh/upgrade/legacy/concurrency와 전체 quality gate를 수행한다.
- live provider/Supabase 적용, push와 PR은 이 source 재통합 단계에서 수행하지 않는다.

## 2026-09-06 Astra finding 복구 보정

- 유실된 임시 worktree를 보존된 Red `88941ade`에서 새 recovery worktree로 연결하고 Java focused `20건 중 7 fail/2 skip`, Python `4건 중 1 fail`을 다시 재현했다.
- `JdbcTimetableImportStore`와 `JdbcTimetableCatalog`가 실제 transaction/exception-translation advisor의 CGLIB proxy target이 될 수 있도록 열고 DB-free proxy 생성을 검증했다.
- route scope migration은 `ACCESS EXCLUSIVE` lock transaction에서 기존 trigger 함수를 backfill 전용 `SECURITY INVOKER`, 빈 `search_path`, 비공개 ACL guard로 잠시 교체한다. 신규 두 컬럼이 모두 NULL인 OLD에서 source/provider city를 exact 복사하고 그 두 컬럼을 제외한 OLD/NEW JSON이 같은 UPDATE만 허용한다. audit 직후 strict guard와 ACL을 복원하며 legacy 행을 삭제하거나 source identity를 바꾸지 않는다.
- replay identity는 raw XLSX SHA, 전체 정규화 operator mapping fingerprint, canonical manifest, record keys, omissions를 4-byte big-endian UTF-8 길이 frame으로 구분한 SHA-256이다. advisory transaction lock 안의 재검사도 이 `importFingerprint`를 사용하고 run metadata에 mapping/import fingerprint를 모두 보존한다. fingerprint 없는 legacy run은 replay하지 않고 conflict로 처리한다.
- anchored attribute 검사와 open이 inode-atomic하지 않음을 명시하고 open을 bounded daemon worker로 옮겼다. 별도 pathname writer cleanup은 FIFO inode 재교체 때 다시 block할 수 있어 제거했다. 대신 anchored `READ+WRITE+NOFOLLOW` 단일 handle을 열고 seekability를 확인한 뒤 읽어 regular→FIFO swap을 bounded fail-closed하며, import 파일에는 읽기·쓰기 권한을 요구한다. macOS filesystem의 `SecureDirectoryStream` 의존 성공/실제 FIFO 테스트는 명시적으로 skip되고, mock 기반 swap/interrupt/worker-zero 회귀는 통과한다. Linux 실제 `mkfifo` 회귀와 disposable PostgreSQL migration 검증은 승인 범위 밖이라 후속 QA로 남긴다.
- timeout 또는 caller interrupt와 동시에 open이 끝나는 경우 `Future` cancellation이 반환값을 폐기할 수 있으므로, 별도 channel handoff가 소유권을 caller 또는 worker cleanup 한쪽에만 넘긴다. interrupt를 무시하고 늦게 반환하는 결정적 회귀에서도 channel close 1회와 worker 0을 검증한다.
