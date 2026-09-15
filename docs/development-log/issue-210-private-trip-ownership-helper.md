# Issue #210 private 여행 소유권 helper 개발 기록

## 범위와 기준

- 누적 source 기준: `3fb07f530e6c668d470ea94ebe59bb56b866ddaf`
- 브랜치: `refactor/210-private-trip-ownership-helper`
- 선행 #46이 private helper를 사용하는 2개 policy와 `public.owns_trip_plan`을 사용하는 17개 policy를 하나의 비노출 helper로 통합한다.
- 아직 선행 변경과 누적 스택이 `develop`에 반영되지 않았으므로 이 결과는 preparatory source이며 배포 완료 상태가 아니다.

## Red → Green → Refactor

운영 SQL보다 먼저 `scripts/tests/test_private_trip_ownership_helper.py`를 추가했다. 최초 실행은 `20260917000000_private_trip_ownership_helper.sql` 부재로 7개 테스트가 모두 실패했다.

Green에서는 `timing_jeju_private.owns_trip_plan(uuid)` 하나를 `STABLE SECURITY DEFINER SET search_path=''`로 만들고 19개 owner SELECT policy를 전환했다. `authenticated`에는 private schema `USAGE`와 정확한 함수 `EXECUTE`만 허용하고 `PUBLIC`·`anon`·`service_role` 접근은 회수했다. 기존 두 helper는 모든 policy 의존성을 제거한 다음 `WITHOUT CASCADE`로 삭제한다. `trip_plans_owner_select`는 재귀 방지를 위해 직접 `user_id = auth.uid()` 판정을 유지하고, generation candidate는 자신의 `trip_plan_id`, recovery change는 기존 recovery option 부모 경로를 유지한다.

Refactor에서는 PostgreSQL 16/17 고정 이미지 Testcontainers source에 extra legacy dependency에 의한 migration 원자 rollback, replay, policy·grant·RLS·data 불변 검사를 추가했다. 별도 actual-RLS SQL은 owner/other/missing/null/malformed subject, 동일 connection subject 전환, `pg_temp` shadow, timeout, ACL/catalog와 19개 policy 행 필터를 검증한다. 교차 테이블 SELECT grant는 단일 transaction 안에서만 부여하고 마지막에 rollback한다.

## 보안 판단과 검증 제한

Supabase 최신 RLS·Database Functions 문서에 따라 definer 함수의 빈 `search_path`, 완전 수식 객체, 비노출 schema, 기본 PUBLIC EXECUTE 회수 원칙을 적용했다. SECURITY DEFINER는 authenticated에 `auth` schema와 `trip_plans` 직접 SELECT를 열지 않고 parent RLS 재귀를 끊기 위해서만 사용한다. 함수는 boolean `EXISTS` 외에 동적 SQL, 쓰기, 로그와 상세 오류를 사용하지 않으며 malformed subject는 false로 닫힌다.

로컬에 Supabase CLI가 없어 `supabase --version`, `supabase --help`, `supabase migration new --help` 탐색은 `command not found`로 끝났다. 기존 최종 timestamp와 init slot을 감사한 뒤 충돌 없는 `20260917000000`/`048` 파일을 수동 생성했다. 요청 범위에 따라 actual PostgreSQL/Testcontainers, Docker, live Supabase, 전체 quality gate와 clean check는 실행하지 않았다.

## Astra MAJOR 보정

최초 PG16/17 parameterized 테스트가 migration·catalog와 비어 있는 table count만 확인하고 실제 fixture/RLS 계약 SQL을 실행하지 않는다는 지적을 받았다. 또한 actual-RLS SQL이 policy 검사 전에 `trip_plans` SELECT를 임시 허용해 canonical parent ACL 회귀를 가릴 수 있었다.

먼저 actual test source의 seed/계약 호출, canonical ACL의 전후 42501, migration의 `trip_plans` SELECT·`auth` USAGE 확대 mutation 거부를 정적 계약으로 추가했다. focused 테스트 9개 중 신규 2개가 실제 wiring과 canonical ACL proof 부재로 실패했다.

보정 후 같은 parameterized Testcontainers 경로가 PG16·PG17 각각에서 migration rollback/replay 뒤 canonical seed와 단일 actual-RLS SQL을 실행한다. 정상 계약 후 helper의 user 비교를 약화한 mutation migration을 적용하고 동일 계약이 실패하는지도 확인한다. actual-RLS SQL은 임시 grant 전에 helper와 기존 owner-readable 2개 policy를 실행하며, `trip_plans`와 `auth.users` 직접 접근의 SQLSTATE 42501 및 `auth` schema USAGE 부재를 검증한다. 전체 19개 policy를 위한 transaction-local grant에는 `trip_plans`를 포함하지 않고 rollback 뒤 parent/auth 42501을 다시 검증한다. actual PostgreSQL 자체는 승인 범위에 따라 실행하지 않았다.

후속 Astra 검토에서 PostgreSQL 16/17 `psql`의 `\quit 1` 인자가 신뢰할 수 없어 assertion 실패가 exit 0으로 끝날 수 있다는 문제가 확인됐다. 먼저 actual 계약에서 인자 있는 `\quit`을 금지하고 owner-deny, other-allow, parent ACL 확대 mutation 세 가지가 같은 contract를 nonzero로 끝내야 한다는 정적 테스트를 추가했다. 신규 테스트는 남아 있던 `\quit 1`을 정확히 찾아 Red가 됐다.

보정에서는 성공·실패 결과를 session-local 임시 assertion table에 boolean과 비식별 label로 모은다. canonical role을 해제한 뒤 `DO` block이 실패 label을 발견하면 `RAISE EXCEPTION`하고, 이 구간은 `ON_ERROR_STOP on`이므로 PG16/17 psql process가 반드시 nonzero로 끝난다. Testcontainers source는 정상 contract exit 0 뒤 helper owner를 모두 거부하는 mutation, 다른 사용자를 허용하는 mutation, `trip_plans` SELECT를 여는 mutation을 각각 적용하고 contract의 nonzero를 검증한다.

## 2026-09-08 actual PostgreSQL QA 보정

- 기준: `origin/develop` `4023095e20acbde6e69f0bbf7b842a252a55cd8f`
- 브랜치: `fix/210-pg16-pg17-actual-rls-qa`
- live Supabase·운영 DB·migration 적용은 수행하지 않았다.

Historical HEAD `d7ee4a5d3a9cfd43e1f6d01d402a6d8786ab6427`에서 PG16/17 모두 `seed_fixtures.sql (exit=3)`로 실패한 원인은 actual-RLS 검증용 전역 seed가 위치 정리 migration 뒤에 실행된 순서였다. 위치 정리에서 제거된 열을 fixture가 다시 쓰기 때문에 ownership helper 자체를 검증하기 전에 seed가 중단됐다. 최신 develop은 #215에서 시간표 migration 뒤 seed를 넣고, 그 다음 위치 정리와 ownership helper를 적용하도록 순서를 교정했으며 동일 Testcontainers 명령이 PG16/17 2건 모두 통과했다.

이번 QA는 이 회귀를 Docker 실행에만 맡기지 않도록 `seed → location cleanup → helper` 순서를 Python 계약으로 고정했다. 또한 기존 test factory가 실패 시 psql stdout/stderr 전체를 예외에 붙여 fixture 값이나 상세 오류가 로그로 확산될 수 있던 경계를 보정했다. psql은 메시지 대신 오류 분류만 남기는 `VERBOSITY=sqlstate`로 실행하고, 첫 `ERROR:` 행만 선택해 임의 container 경로·single-quoted literal·UUID를 치환하며 512자로 제한한다. 실제 legacy dependency 실패 assertion도 dependency SQLSTATE `2BP01`과 `error=psql:` 요약은 남고 `stdout=`·`stderr=` 원문은 남지 않는지 확인한다.

TDD Red는 `python3 -m unittest scripts.tests.test_private_trip_ownership_helper -v`에서 신규 안전 진단 계약이 psql verbosity 제한과 안전 요약 부재로 1건 실패했다. 최소 구현 뒤 같은 12개 Python 계약과 Docker-free `./gradlew test --tests 'com.timingjeju.api.support.postgresql.PostgreSqlTestContainerFactoryTest' spotlessCheck`가 통과했다. 변경 후 첫 actual-PG 실행은 두 버전 모두 안전한 `2BP01`을 반환했지만 psql이 `ERROR:` 뒤에 공백 두 칸을 출력해 exact assertion이 실패하는 두 번째 Red가 됐다. 요약의 모든 공백을 단일화한 뒤 같은 parameterized 테스트를 다시 실행해 PG16/PG17 2건 모두 통과했다.

### Reviewer 진단 redaction 보정

Reviewer는 첫 구현이 single quote와 UUID만 직접 치환해 double-quoted PostgreSQL 값, `$$...$$`, tagged dollar quote, 일반 filesystem path가 synthetic stderr에 들어오면 남을 수 있고, 512자 뒤 ellipsis를 붙여 실제 최대 길이가 513자가 되는 문제를 찾았다. 먼저 factory unit test에 모든 민감 형식을 한 진단에 섞은 case와 긴 `42501` 진단 case를 추가했다. 신규 2개 테스트는 double/dollar/path 노출, 원인 범주 부재, 513자 결과를 각각 보여 Red가 됐다.

Green에서는 `ERROR:` 뒤 5자리 SQLSTATE를 민감 detail보다 먼저 분리하고 SQLSTATE class를 `dependent-objects`, `syntax-or-access-rule`, `integrity-constraint` 같은 제한된 원인 범주로 매핑한다. 그 뒤에만 single/double quote, untagged/tagged dollar quote, Unix·Windows path, UUID를 `<redacted>`로 치환한다. 긴 결과는 SQLSTATE와 원인 범주가 항상 앞에 있도록 구성한 뒤 ellipsis를 포함해 총 512자로 제한한다. SQLSTATE를 파싱하지 못하면 원문을 반사하지 않고 generic `database-error; details=<redacted>`로 닫힌다. Docker-free synthetic unit 5개, Python 계약 12개와 Spotless가 통과했다.

### Full gate cross-test 정렬

Exact HEAD `1a5748af` full quality gate는 Spring integration 730개 중 `CanonicalMigrationOrderIntegrationTest` 1개가 실패해 24분 33초에 종료됐다. 충돌용 constraint가 migration을 실제 SQLSTATE `42710`으로 중단했지만, 기존 assertion이 redaction 전의 raw constraint name을 예외 메시지에서 요구했기 때문이다. 이 full gate 실패 자체를 Red 증거로 삼았다.

테스트 목적을 재검토해 exception type과 실패 뒤 기존 constraint 상태 불변, 신규 열 0개, 충돌 제거 뒤 migration 성공과 oversized legacy 보존 검증은 그대로 유지했다. 메시지 assertion만 민감 identifier 대신 `ERROR: 42710`과 제한된 `cause=syntax-or-access-rule`을 요구하도록 정렬했다. 이로써 migration failure를 숨기지 않으면서 factory의 arbitrary stderr 비노출 계약을 유지한다.

정렬 후 해당 단일 integration 메서드를 다시 실행해 PG16/17 모두 1분 37초에 통과했다. 이어서 diagnostic redaction unit 5개, Python 계약 12개와 Spotless를 재검증했다.
