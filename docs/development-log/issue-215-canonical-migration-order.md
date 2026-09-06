# Issue #215 canonical migration 순서 안전화

## 배경

`origin/develop` `6cfa98fd3e65ba270eceea7150c843b33dbe2a56` 뒤의 누적 브랜치는 PR 병합 순서와 migration timestamp가 역전되어 있었고, #50과 #51이 같은 `20260907000001` 경로를 서로 다른 blob으로 사용했다. 이 상태에서는 먼저 기록된 migration 파일이 후속 병합에서 바뀔 수 있다.

## Red

운영 파일을 바꾸기 전에 `scripts/tests/test_canonical_migration_order.py` 5개 테스트를 추가했다. `python3 -m unittest scripts.tests.test_canonical_migration_order -v` 결과는 `FAILED (failures=4, errors=1)`이었다. canonical suffix 12개, #50 baseline 경로, manifest, compose/init 정렬, actual PostgreSQL 검증 소스가 모두 없다는 이유로 실패했다.

## Green과 Refactor

- `origin/develop`의 35개 migration과 마지막 `20260907000000`의 SHA-256을 manifest에 동결했다.
- 후속 migration을 `20260918000000..011`, Docker init `038..049`로 재배치했다. seed는 `099`를 유지했다.
- #50 `20260918000007`은 commit `0d275a28`의 blob `dbd52ac6`과 byte-for-byte 동일한 baseline이다.
- #51 `20260918000008`은 강화된 legacy preflight, CHECK, trigger/function/assertion과 정확한 ACL만 교체한다. #50의 composite FK/index와 sealing helper rename은 반복하지 않는다.
- 세 compose, POSIX shell과 PowerShell smoke의 fresh/origin-develop upgrade/#50→#51/concurrency 경로를 manifest 순서에 맞췄다.
- 실제 disposable PostgreSQL을 위한 Testcontainers 소스와 schema·ACL fingerprint SQL을 추가했다.

Supabase CLI는 로컬에 없어 `supabase --help`가 `command not found`로 끝났다. 공식 문서상 remote history는 timestamp를 기준으로 비교하고 적용된 migration을 건너뛰므로, 동일 경로 overwrite 대신 고유 additive timestamp를 사용했다.

## 환경 검증 범위

후속 승인에 따라 disposable PostgreSQL 16/17, 실제 Testcontainers, Docker smoke, Spring `clean check`와 루트 quality gate를 로컬에서 검증한다. live Supabase, production DB와 deployment는 계속 금지한다.

## 검증 결과

- canonical migration 계약: 9건 성공
- 관련 Python 계약: 70건 성공
- 전체 Python 회귀: 782건 성공, 환경 조건 skip 3건
- Gradle `unitTest`: 1,372건 성공, 환경 조건 skip 9건
- Gradle `architectureTest`: 46건 성공
- 변경된 migration Java 계약: 16건 성공
- `spotlessCheck`, shell syntax, manifest JSON, deploy SQL 정책, REST/domain 정적 validator, diff whitespace, 전체 파일 비밀정보 검사 성공

로컬에는 `supabase` CLI와 `pwsh`가 없어 각각 실제 CLI 검증과 PowerShell parser 검증은 실행하지 못했다. PowerShell smoke는 manifest 기반 동적 replay와 정적 계약으로 검증하고, POSIX Docker smoke를 실제 실행한다.

## 로컬 PostgreSQL QA 보정

- 최초 실제 Testcontainers 집중 실행은 19건 중 8건 실패로 fingerprint scalar 직렬화, Storage fixture schema 권한, JDBC array 비교 문제를 재현했다. 보정 후 관련 3개 클래스 20건이 모두 성공했다.
- 첫 `./gradlew clean check`는 724건 중 13건 실패로 끝났다. 원인은 누적 migration을 반영하지 못한 세 fixture 클래스였고 운영 adapter 오류가 아니었다.
- fixture를 정식 required-reference와 timetable migration 순서에 맞춘 뒤, 회귀 3개 클래스와 #215 핵심 3개 클래스를 합친 75건이 모두 성공했다.
- 이 과정에서 #51이 허용한 제목 기반 `meal/free_time/custom`을 과거 core seal 함수가 다시 거부하는 누적 스키마 충돌을 발견했다. frozen migration을 수정하지 않고 `20260918000012_schedule_title_only_sealing_correction.sql`을 추가해 core location 검사만 좁혔으며, PG16/17에서 제목 기반 item seal 성공과 blank title 거부를 확인했다.

## 독립 리뷰 보정

Astra 리뷰에서 PostGIS aggregate에 대한 `pg_get_functiondef` 오류 가능성, PowerShell upgrade DB의 Auth 호환 bootstrap 누락, security fingerprint의 ACL 투영 부족을 지적했다. 보정 테스트를 먼저 추가한 결과 canonical 계약 9건 중 3건이 실패했다. 이후 extension-owned routine과 aggregate를 제외하고 application function/procedure/window routine만 fingerprint하며, PUBLIC relation ACL·column ACL·public/private/auth schema owner/ACL·policy permissive/roles를 정규화했다. 실제 PostgreSQL 테스트 소스는 PostGIS PG16/17 비어 있지 않은 fingerprint와 민감 column/PUBLIC relation/schema/policy 변조 탐지를 고정한다. PowerShell은 두 replay DB 모두 `001_auth_compat.sql`을 immutable prefix보다 먼저 적용하는 공용 helper를 사용한다.
