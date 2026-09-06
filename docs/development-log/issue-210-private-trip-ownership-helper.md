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
