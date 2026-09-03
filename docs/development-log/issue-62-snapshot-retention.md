# Issue #62 외부 snapshot retention 운영 정리 개발 기록

## 범위 확정

- 기준 브랜치: 최신 `origin/develop`의 #31 병합 commit `d53254c`
- 선행 이슈 #23, #30, #39, #41, #43, #75, #76은 모두 CLOSED다.
- #164가 payload-only one-shot batch를, #166이 default-off scheduler·bounded retry·metrics를
  구현했다.
- #41은 TMAP mobility 원문·상세 geometry·사용자 위치를 영속하지 않는 provider-neutral 경계를
  확정했다. 따라서 #62의 전체 provider는 실제 raw snapshot을 영속하는 승인 공급자 전체인
  `TAGO`, `kma`, `tour-api`를 뜻한다.

## First RED

운영 코드보다 먼저 canonical 영속 공급자 catalog와 중앙 저장 fail-closed 테스트를 추가했다.

```bash
cd services/spring-api
./gradlew --no-daemon unitTest \
  --tests 'com.timingjeju.api.application.snapshot.PersistedSnapshotProviderCatalogTest' \
  --tests 'com.timingjeju.api.application.snapshot.SnapshotStoreServiceTest.TMAP_payload는_redaction과_store_호출_전에_영속화를_거부한다' \
  --tests 'com.timingjeju.api.application.retention.SnapshotRetentionServiceTest.한_batch는_Clock을_한번만_읽고_canonical_영속_snapshot_공급자_전체를_전달한다' \
  --console=plain
```

`PersistedSnapshotProviderCatalog`가 없어서 `compileTestJava` missing-symbol 7건으로 실패했다.
Issue #62 댓글 `5504519923`에 운영 코드 변경 0인 첫 RED를 기록했다.

## Green과 경계 정리

- snapshot application package에 immutable canonical provider catalog를 추가했다.
- `SnapshotStoreService`가 catalog 밖 provider를 payload redaction과 store 호출 전에 거부한다.
- retention command/service가 data-health catalog가 아니라 같은 영속 snapshot catalog를 사용한다.
- 기존 완료 공급자 전용 catalog를 제거해 data-health operation 구성과 retention 생명주기를 분리했다.
- TMAP은 retention 대상으로 가장하지 않고 비영속 경계를 유지한다.
- snapshot identity 차이 테스트와 DB scope mismatch fixture는 승인된 `TAGO` provider로 교정했다.

## 유지되는 운영 계약

- row를 DELETE하지 않고 만료된 `raw_payload`만 NULL로 바꾸며 `purged_at`을 기록한다.
- snapshot identity, hash, status, import run과 normalized lineage를 보존한다.
- running import run은 건너뛰고 snapshot/run을 `FOR UPDATE SKIP LOCKED`로 잠근다.
- batch 최대 500, cycle 최대 10, dry-run 1 batch, retry 최대 3 attempts를 유지한다.
- 원문, 식별자, SQL과 예외 원문은 log·metric·trace에 남기지 않는다.

## 검증

- focused unit·retention scheduler·Architecture·Spotless: 성공
- 관련 Python 정적 계약: 21/21, 실패 0
- actual PostgreSQL: retention 8/8, snapshot store 15/15, 실패·오류 0
- Spring `clean check`: 13분 5초, 기본 1,641건(실패·오류 0, skip 8), integration
  475건(실패·오류 0, skip 2)
- JaCoCo report: line 16,034/17,714(90.51%), branch 5,471/7,792(70.21%)
- OpenAPI, Architecture, Spotless, `git diff --check`: 성공
