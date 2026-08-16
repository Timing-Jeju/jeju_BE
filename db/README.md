# Timing Jeju 데이터베이스 개발 환경

## 환경별 연결 관계

| 환경 | Auth | PostgreSQL/PostGIS | 사용자 데이터 |
| --- | --- | --- | --- |
| 로컬 Supabase | `http://127.0.0.1:54321/auth/v1` | `127.0.0.1:54322/postgres` | 로컬 Docker 볼륨에만 저장 |
| 일반 PostgreSQL 스모크 테스트 | `db/local-postgres/auth_compat.sql`의 최소 호환 객체 | 격리된 PostGIS 16 컨테이너 | 가짜 fixture만 저장하고 종료 시 삭제 |
| 운영 | 호스팅된 Supabase Auth | 호스팅된 Supabase PostgreSQL/PostGIS | 해당 운영 프로젝트 안에서만 저장 |

로컬과 운영은 서로 다른 Auth·DB 인스턴스이므로 사용자와 데이터가 공유되지 않습니다. 운영 URL, DB 비밀번호, publishable key 등 실제 값은 배포 플랫폼의 비밀 저장소에서 주입하고 저장소나 로그에 남기지 않습니다.

Spring과 클라이언트가 사용하는 환경 변수 이름은 다음과 같습니다.

- `SUPABASE_URL`: 로컬 API URL 또는 운영 프로젝트 URL
- `SUPABASE_PUBLISHABLE_KEY`: 클라이언트용 publishable key
- `SPRING_DATASOURCE_URL`: 환경별 PostgreSQL JDBC URL
- `SPRING_DATASOURCE_USERNAME`: 환경별 DB 사용자
- `SPRING_DATASOURCE_PASSWORD`: 환경별 DB 비밀번호

Spring은 Supabase access token을 JWKS로 검증합니다. 인증 환경 변수와 로컬/운영 HTTPS 경계는 [인증 문서](../docs/AUTHENTICATION.md)를 따릅니다. DB 접속 비밀번호와 `service_role`은 브라우저·FastAPI에 전달하지 않습니다.

## 마이그레이션 소유권

`supabase/migrations`가 public 애플리케이션 스키마의 유일한 버전 관리 기준입니다. Supabase CLI가 로컬과 운영에 같은 파일을 순서대로 적용합니다.

운영 또는 공유 환경에 한 번이라도 적용된 마이그레이션 파일은 수정하지 않습니다. 이후 변경은 항상 더 큰 timestamp의 새 마이그레이션으로만 추가합니다. 아직 병합·배포되지 않은 작업 브랜치의 마이그레이션은 PR 승인 전까지 하나의 변경 세트로 검증합니다.

- `supabase/migrations/20260728000000_initial_public_schema.sql`: PostGIS 확장과 public 애플리케이션 객체
- `supabase/migrations/20260730000000_database_integrity_hardening.sql`: import 실행 상태, 일정 소유권과 관계 무결성 강화
- `supabase/migrations/20260730010000_external_ingestion_foundation.sql`: 원천 snapshot, checkpoint와 정규화 lineage 기반
- `supabase/migrations/20260730020000_ingestion_consistency_hardening.sql`: 최신 TourAPI 코드, 수집 lineage, TAGO 범위 키와 유효기간 충돌 차단
- `supabase/migrations/20260730030000_schedule_consistency_hardening.sql`: 확정 일정 일자·시간·계산 결과와 버전 계보 불변성 강화
- `supabase/migrations/20260730040000_import_run_lineage_retention.sql`: origin과 무관한 정규화 provenance import run 삭제 차단
- `supabase/migrations/20260810000000_api_idempotency_registry.sql`: 변경 API 멱등성 scope·lease·TTL·최소 응답 저장 계약
- `supabase/migrations/20260811000000_async_run_worker_runtime.sql`: compute run lease·heartbeat·fencing·retry·stuck recovery 상태 계약
- `supabase/migrations/20260813000000_import_run_lifecycle_fencing.sql`: import run 내부 쓰기 owner/fencing 불변 계약
- `supabase/migrations/20260813010000_external_snapshot_storage.sql`: snapshot redaction·크기·상태·retention 감사 계약
- `supabase/migrations/20260814000000_tour_api_operation_provenance.sql`: TourAPI operation registry와 normalized row 다중 계보 계약
- `supabase/migrations/20260816000000_tour_api_detail_info_operation.sql`: 반복 상세 `detailInfo2` operation 등록
- `supabase/migrations/20260817000000_tour_api_place_images_operation.sql`: 반복 이미지 `detailImage2` sweep와 operation 계보 등록
- `supabase/migrations/20260818000000_tour_api_incremental_sync.sql`: TourAPI 증분 동기화 cursor와 complete sweep 계약
- `supabase/migrations/20260819000000_tago_stop_import.sql`: TAGO 제주 도시코드·정류장 full import와 freshness scope 계약
- `supabase/migrations/20260822000000_place_stop_postgis_links.sql`: PostGIS 관광지-정류장 후보 link lifecycle·freshness와 complete scope watermark 계약
- `supabase/seed.sql`: 운영 적용 가능한 빈 시드
- `db/local-postgres/auth_compat.sql`: Supabase가 아닌 일반 PostgreSQL 전용 Auth 호환 계층
- `db/local-postgres/seed_fixtures.sql`: 일반 PostgreSQL Docker 스모크 테스트 전용 가짜 데이터
- `db/queries/smoke_check.sql`: 기존 fixture와 공간 쿼리 확인
- `db/queries/schema_contract.sql`: 데이터 변경 없이 객체·제약·인덱스·RLS를 확인하는 스키마 계약
- `db/queries/database_negative_constraints.sql`: 트랜잭션 안에서 잘못된 입력이 거부되는지 확인하고 rollback하는 음수 계약
- `db/queries/legacy_v1_upgrade_fixture.sql`, `legacy_foundation_running_scope_fixture.sql`, `legacy_v1_upgrade_contract.sql`: v1 데이터와 외부 적재 기반까지 생성된 legacy 중복을 최신 마이그레이션까지 실제 재생하는 보존·격리·복구 계약
- `db/queries/legacy_foundation_checkpoint_status_conflict_fixture.sql`, `legacy_foundation_checkpoint_scope_conflict_fixture.sql`: checkpoint가 실패 run 또는 다른 source 범위 run을 참조하면 식별자와 함께 중단하는 계약
- `db/queries/legacy_foundation_unparsed_lineage_conflict_fixture.sql`, `legacy_foundation_run_lineage_conflict_fixture.sql`, `legacy_foundation_source_lineage_conflict_fixture.sql`: 기존 정규화 행의 snapshot 상태·run·source 범위 계보 위반을 식별자와 함께 중단하는 계약
- `db/queries/legacy_foundation_external_reference_conflict_fixture.sql`, `legacy_foundation_timetable_conflict_fixture.sql`, `legacy_foundation_open_closed_conflict_fixture.sql`, `legacy_foundation_multi_snapshot_scope_fixture.sql`: 기준 코드·시간표·영업시간 유효기간 충돌과 한 실행의 다중 snapshot 범위를 식별자와 함께 중단하는 계약
- `db/queries/legacy_v1_cross_day_conflict_fixture.sql`, `legacy_v1_result_day_conflict_fixture.sql`, `legacy_v1_recommendation_day_conflict_fixture.sql`, `legacy_v1_base_lineage_conflict_fixture.sql`: 익일 영업시간, 날씨·추천 결과 Day, 일정 base 계보가 잘못된 legacy DB의 명시적 마이그레이션 중단 계약
- `db/queries/database_concurrency_contract.sql`: 삭제되는 테스트 DB에서만 `dblink`를 사용해 체크포인트·일정의 실제 2세션 경쟁을 검증하는 계약

운영 마이그레이션은 `auth` 스키마, `auth.users`, `auth.uid()`를 생성·교체·삭제하지 않으며 `auth.users`에 직접 INSERT하지 않습니다. `auth.users` 외래키와 `auth.uid()`를 사용하는 RLS 정책은 Supabase 소유 객체를 참조할 뿐 변경하지 않으므로 유지합니다.

관광지-정류장 후보 연결 배치는 DB에 동기화된 장소·정류장 좌표만 사용하며 사용자 실시간 위치, 길찾기 provider credential, 새 환경변수를 요구하거나 저장하지 않습니다.

현재 기능 개발 로드맵 전체에서 Flyway는 도입하지 않습니다. Flyway 의존성·설정·`db/migration`을 추가하지 않고, 도입 여부는 모든 주요 기능 개발이 끝난 뒤 마지막 안정화 GitHub Issue에서만 검토합니다. 운영에 `db/local-postgres` 파일을 적용하거나 이 파일을 `supabase db push` 대상으로 복사하면 안 됩니다.

## 외부 데이터 적재와 read model

외부 API 응답은 공개 조회 테이블에 바로 덮어쓰지 않습니다. Spring의 서버 전용 수집 경로가 `data_import_runs`에 실행을 만들고, 응답과 redaction된 요청 메타데이터를 `external_api_snapshots`에 원형 snapshot으로 보존한 뒤, 검증과 파싱을 통과한 값만 장소·교통·날씨 정규화 read model에 반영합니다. 공개 API와 계산 계층은 정규화 read model을 읽으며 raw payload를 응답이나 계산 입력으로 직접 노출하지 않습니다.

외부 정규화 행의 `import_run_id`와 `source_snapshot_id`는 실행 → 원천 snapshot → read model lineage를 연결하며 둘 다 필수입니다. DB는 `parsed` 또는 `tombstoned` snapshot만 허용하고 run·provider·service·operation·scope 일치를 검사합니다. 같은 snapshot과 run으로 반복한 upsert는 정규화 내용이 같을 때만 멱등 처리합니다. 내용이 달라지면 새 snapshot과 그 snapshot의 matching run을 함께 연결해야 하며, 이 방식의 정상 재수집·재파싱 repair는 허용합니다. `manual`·`fixture`·`admin_upload`처럼 명시된 앱 입력만 snapshot 예외이며, 이전 행과 새 행이 모두 같은 예외 성격을 유지하는 동안 일반 앱 입력처럼 수정할 수 있습니다. 예외 행에 run을 연결하면 그 run의 범위도 같아야 합니다. 외부 lineage 없는 legacy 행과 snapshot-backed 외부 행 모두 source marker를 예외 값으로 바꾸면서 lineage를 제거할 수 없습니다. 기존 marker가 이미 예외 값이어도 OLD snapshot과 run의 실제 `source_kind`·provider가 외부이면 살아 있는 snapshot 포인터와 run을 제거할 수 없습니다. retention 작업이 원문 snapshot을 삭제할 때는 정규화 내용과 `import_run_id`를 유지하고 `source_snapshot_id` 포인터만 비우며, 새 원문을 연결하기 전에는 내용이나 마지막 run을 바꿀 수 없습니다. `data_import_runs`는 외부·fixture·admin 적재를 같은 방식으로 감사하는 provenance ledger입니다. 따라서 16개 정규화 테이블 중 하나라도 run을 참조하면 snapshot 유무와 origin marker에 관계없이 부모 DELETE를 `23503`으로 거부하고, 미참조 succeeded·failed·fixture·admin run만 삭제할 수 있습니다. catalog audit는 정확한 16개 table/column FK mapping을 보장합니다. 8개 `NO ACTION`과 8개 `SET NULL`의 선언은 유지하지만 `BEFORE DELETE` guard가 FK referential action보다 먼저 live reference를 검사하므로 삭제 정책은 `confdeltype`에 의존하지 않습니다. 마이그레이션은 기존 non-NULL lineage와 snapshot-backed optional marker 불일치를 16개 정규화 테이블 전체에서 소급 감사해 위반 table·row·snapshot·run·실제 origin·scope를 출력하고 중단합니다.

한 `data_import_runs` 실행의 `source_kind`·provider·service·operation·scope는 생성 후 불변이고, 연결된 모든 snapshot은 정확히 그 범위를 공유해야 합니다. legacy 다중 범위 실행이 발견되면 자동으로 범위를 선택하지 않고 `import_run_id`와 충돌 범위를 출력하며 마이그레이션을 중단합니다. 운영자는 원본 의미에 따라 snapshot을 범위별 별도 run으로 분리하거나 격리한 뒤 마이그레이션을 다시 적용해야 합니다.

`data_import_runs.idempotency_enforced`와 `running_scope_enforced`는 legacy 중복을 삭제하지 않고 신규 멱등·동시 실행 계약을 활성화하는 marker입니다. 정상 신규 행은 두 marker가 항상 `true`이며 애플리케이션이 `false`로 삽입하거나 내릴 수 없습니다. 멱등 중복 그룹은 `(started_at, id)` 기준 가장 오래된 한 행만 `idempotency_enforced=true`인 canonical arbiter로 남고 후속 중복을 `false`로 격리합니다. 이 canonical 행은 같은 키의 grandfathered 행이 남아 있는 동안 먼저 삭제할 수 없고, marker 조건 partial unique index가 신규 importer의 `ON CONFLICT` 기준입니다. 실행 중 범위 중복도 가장 오래된 한 행만 `running_scope_enforced=true`로 두지만, 후속 `false` 행이 남아 있는 범위에 새 run을 삽입하거나 재시작하면 BEFORE trigger가 직접 `23505`로 거부합니다. running marker는 `ON CONFLICT` arbiter나 canonical 삭제 보호를 의미하지 않습니다. 두 종류의 `false` 행은 충돌과 길이를 해소한 뒤에만 marker를 `true`로 올려 복구합니다.

Spring 생명주기 adapter가 만든 모든 run은 임의 UUID `owner_token`과 양수 `fencing_token`을 갖습니다. 두 값은 생성 후 DB trigger로 불변이며, count 누적과 terminal 전이는 run ID·owner·fencing·`running` 상태가 모두 일치할 때만 허용됩니다. owner token은 외부 API key나 사용자 token이 아니라 내부 쓰기 권한이지만 로그와 공개 응답에는 노출하지 않습니다. count overflow와 상태 전이 실패는 단일 SQL 문장 전체를 취소해 부분 count나 terminal marker를 남기지 않습니다.

provider·service·operation·scope와 source key/payload hash를 포함한 unique 계약은 같은 응답을 재수집해도 upsert 가능한 멱등 경계를 제공합니다. 기준 코드, 시간표, 같은 요일 open/closed 영업시간의 legacy 유효기간 충돌은 exclusion constraint를 설치하기 전에 pair audit로 검사합니다. 충돌을 자동 삭제·병합하지 않고 정확한 `left_id`/`right_id`를 출력해 중단하므로 운영자가 원천 기준으로 기간을 정리하거나 행을 격리한 뒤 재적용해야 합니다. `data_import_checkpoints`는 같은 범위의 `succeeded` 실행만 마지막 성공 지점으로 참조하며 source scope와 version이 불변입니다. checkpoint와 run 양쪽 write guard를 먼저 설치한 뒤 기존 status/scope 참조도 감사하므로 설치 중 상태 전이 race를 허용하지 않습니다. 서버는 `advance_data_import_checkpoint(...)`에 기대 version을 전달해 원자적으로 한 단계만 전진하고 stale writer는 `40001`로 실패합니다. 직접 UPDATE·DELETE·TRUNCATE와 이전 run으로의 역행은 금지하며 `anon`·`authenticated`는 이 함수를 실행할 수 없습니다. `service_role`도 테이블을 직접 갱신하지 않고 함수만 실행합니다.

`data_import_checkpoints`, `external_api_snapshots` 등 수집 내부 테이블은 RLS를 활성화하되 `anon`·`authenticated` 정책과 직접 grant를 두지 않습니다. 운영 적재는 비밀 저장소에서 주입한 서버 전용 `service_role`만 사용하고 브라우저·FastAPI MCP에는 이 권한을 전달하지 않습니다. raw payload와 오류 상세에는 API key, token, PII를 저장하지 않으며 보존 기한이 지난 snapshot은 별도 운영 작업에서 정리합니다.

`service_role`은 정상 앱 쓰기에 필요한 SELECT·INSERT·UPDATE·DELETE와 명시적 RPC 권한을 유지하지만, 행 trigger를 우회하는 `TRUNCATE`는 현재와 향후 public 앱 테이블에서 회수합니다. `spatial_ref_sys` 같은 확장 관리 객체는 확장 소유자의 ACL 경계이므로 앱 테이블 권한 검사에서 제외합니다. 파괴적 앱 테이블 초기화는 서버 런타임이 아니라 통제된 migration owner 작업으로만 수행합니다.

정책 검사는 독립적으로 실행할 수 있습니다.

```bash
python3 scripts/deploy_sql_policy.py
python3 -m unittest scripts.tests.test_deploy_sql_policy scripts.tests.test_supabase_layout
```

검사기는 single-quoted string, PostgreSQL E-string, quoted identifier와 Unicode 태그를 포함한 dollar-quoted body를 구분하고 실제 `--`, `/* */` 주석만 제외합니다. 문자열 본문은 동적 SQL일 수 있으므로 보존하며, PostgreSQL `EXECUTE`의 직접 문자열 안에 금지 SQL이 연속된 토큰으로 있으면 보수적으로 정책 위반으로 처리합니다. 단순 안내 문자열에도 같은 금지 SQL 문구를 쓰지 않아야 하며, 금지 객체를 설명할 때는 SQL 파일이 아닌 한국어 문서를 사용합니다.

이 검사는 SQL 실행 의미를 평가하는 범용 파서가 아닙니다. `EXECUTE 'create table auth.' || 'users(...)'` 같은 문자열 연결이나 `EXECUTE`와 `format(...)`으로 런타임에 만들어지는 객체명은 의미 분석 범위 밖입니다. 이런 동적 SQL은 자동 검사 통과만으로 안전하다고 간주하지 않고 코드 리뷰에서 금지 객체 조합 여부를 별도로 확인합니다.

## 로컬 Supabase 시작과 초기화

필수 도구는 Docker Engine, Docker Compose, Supabase CLI `2.110.0`입니다. CLI는 [Supabase CLI v2.110.0 공식 릴리스](https://github.com/supabase/cli/releases/tag/v2.110.0)의 운영체제별 설치 파일을 사용하고 다음 명령으로 버전을 확인합니다.

```bash
supabase --version
```

저장소 루트에서 로컬 Auth와 PostgreSQL/PostGIS를 시작합니다.

```bash
supabase start
supabase db reset
```

`supabase db reset`은 로컬 DB 데이터를 삭제하고 모든 마이그레이션과 `supabase/seed.sql`을 다시 적용하는 로컬 전용 명령입니다. 운영 프로젝트를 연결하거나 운영 DB에 `db push`하지 않습니다.

반복 초기화와 정리까지 한 번에 검증하려면 다음 스크립트를 실행합니다. CLI가 없거나 버전이 다르거나 Docker daemon이 꺼져 있으면 한국어 오류로 즉시 실패합니다. CLI가 표시할 수 있는 로컬 키는 검증 로그에 출력하지 않습니다.

```bash
./scripts/supabase-smoke-test.sh
```

테스트 사용자가 필요하면 애플리케이션의 Supabase Auth 클라이언트 또는 로컬 Auth signup API를 사용해 가짜 사용자를 생성합니다. 비밀번호와 publishable key는 실행 시 환경 변수로만 전달하고 저장소·스크립트·로그에 남기지 않습니다. SQL로 `auth.users`에 직접 INSERT하는 방식은 사용하지 않습니다.

## 일반 PostgreSQL/PostGIS 경로

기존 Spring Docker 검증은 Supabase Auth 컨테이너 대신 로컬 전용 호환 계층을 먼저 적용한 뒤 같은 `supabase/migrations` 기준선을 적용합니다.

Spring Repository 통합 테스트는 `services/spring-api`의 Testcontainers 공통 기반을 사용합니다. 이 경로는 PostGIS 16 격리 컨테이너에 `auth_compat.sql`과 timestamp순 canonical migration만 적용하고 로컬 seed fixture는 적용하지 않습니다. 테스트 연결은 Spring Boot service connection이 임의 포트와 실행별 비밀번호로 제공하며, 각 테스트 트랜잭션과 컨테이너는 실행 후 자동 정리됩니다. PostgreSQL 17은 실제 Supabase CLI reset/smoke 경로에서 별도로 검증합니다.

일반 PostgreSQL 초기화 순서는 Auth 호환 객체 → 최초 public 스키마 → 기본 무결성 강화 → 외부 적재 기반 → 적재 일관성 강화 → 일정 일관성 강화 → 로컬 fixture입니다. fixture보다 앞에서 모든 운영 마이그레이션을 적용하므로 fixture가 신규 제약과 동일한 경로를 검증합니다.

```bash
docker compose up -d postgres
./scripts/docker-smoke-test.sh
```

개발용 PostgreSQL은 기존 로컬 5432 포트와 충돌하지 않도록 `localhost:5433`에 노출됩니다. 직접 초기화하려면 로컬 볼륨을 삭제한 뒤 다시 시작합니다.

```bash
docker compose down -v
docker compose up -d postgres
```

`./scripts/docker-smoke-test.sh`는 clean bootstrap 외에도 실제 v1→최신 재생, checkpoint status/scope, 정규화 lineage와 provenance parent run 삭제, 기준 코드·시간표·영업시간·다중 snapshot 범위·익일 영업시간·결과 Day·일정 base 계보 충돌의 명시적 마이그레이션 중단, 체크포인트 CAS와 일정·교차 요일 영업시간 write-skew의 실제 2세션 대기·재검증을 실행합니다. 일정과 영업시간의 부모 행 MVCC 쓰기 펜스는 오래된 `REPEATABLE READ` writer를 `40001`로 중단합니다. 테스트용 `dblink` 확장은 삭제되는 격리 DB나 종료 시 폐기되는 로컬 Supabase DB에만 설치하며 운영 마이그레이션에는 포함하지 않습니다. 두 스모크 스크립트는 스키마 계약과 rollback 기반 음수 무결성 계약을 모두 실행합니다. Supabase 경로는 PostgreSQL 17을 명시적으로 확인하고 같은 2세션 계약을 재실행하며, 운영용 빈 시드가 신규 수집 테이블에도 행을 만들지 않는지 추가로 검사합니다. 성공·실패와 관계없이 자신이 만든 컨테이너와 임시 DB 자원을 정리합니다.
