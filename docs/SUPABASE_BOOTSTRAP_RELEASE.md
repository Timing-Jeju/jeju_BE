# Supabase 최초 bootstrap release runbook

Issue #242는 최초 canonical schema 배포 전에 `public.rls_auto_enable()`의 직접 실행 권한을
회수한다. 함수 본문과 `ddl_command_end` event trigger는 변경하지 않으며, 신규 `public` table의
RLS 자동 활성화는 계속 동작한다.

## 현재 작업의 안전 경계

- 이 개발 작업에서는 live 적용 금지다. `supabase db push`, Dashboard SQL 실행, 원격 migration
  history 변경을 수행하지 않는다.
- 실제 적용은 최신 HEAD에 대한 독립 Reviewer 승인 후, 운영 담당자가 대상 이름 `Timing-Jeju`와
  정확한 project ref를 Dashboard와 CLI 양쪽에서 다시 확인한 별도 변경 창에서만 수행한다.
- seed fixture는 운영에 적재하지 않는다. `db/local-postgres`는 disposable Docker QA 전용이다.

## 적용 전 점검

다음 읽기 전용 SQL 결과와 실행 시각을 변경 기록에 보존한다. 함수가 없거나 SECURITY DEFINER가
아니거나 event trigger가 비활성화된 경우에는 migration을 적용하지 말고 원인을 조사한다.
canonical 지원 환경은 Supabase의 기존 함수 또는 Docker의 명시적 호환 fixture를 전제로 하며,
대상 함수가 없으면 055 migration은 조용히 건너뛰지 않고 실패해 잘못된 bootstrap을 차단한다.

```sql
select current_database(), current_user, now();

select p.oid,
       n.nspname,
       p.proname,
       p.prosecdef,
       pg_get_userbyid(p.proowner) as owner,
       p.proacl
from pg_catalog.pg_proc p
join pg_catalog.pg_namespace n on n.oid = p.pronamespace
where p.oid = to_regprocedure('public.rls_auto_enable()');

select e.evtname, e.evtevent, e.evtenabled, e.evttags
from pg_catalog.pg_event_trigger e
where e.evtfoid = to_regprocedure('public.rls_auto_enable()');

select count(*) as public_relations
from pg_catalog.pg_class c
join pg_catalog.pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public' and c.relkind in ('r', 'p');

select version
from supabase_migrations.schema_migrations
order by version;
```

`supabase/migrations/manifest.json`의 모든 path, SHA-256, dependency와 init slot을 검증하고,
disposable PostgreSQL 17에서 auth compatibility → immutable prefix → canonical suffix → seed 순서의
전체 replay 및 profile-images 실제 RLS 계약을 통과시킨 뒤에만 운영 변경을 예약한다.

Reviewer 승인과 정확한 project ref 재확인 뒤에도 실제 적용 전에 `supabase db push --dry-run`만
실행해 원격에 적용될 pending migration basename 목록을 저장한다. manifest exact ordered list는
`(.immutablePrefix + .canonicalSuffix)[] | .path | split("/")[-1]`로 path를 canonical basename으로
정규화한 순서이며 dry-run pending basename 목록과 line-by-line으로 대조한다. 마지막 항목은
055이고 seed는 목록에 없어야 한다. basename, 순서, 개수 또는 원격 migration history가 하나라도
mismatch이면 즉시 중단하고 `supabase db push`를 실행하지 않는다.

## 적용 후 점검

권한 회수와 event trigger 보존을 같은 세션에서 확인한다.

```sql
select has_function_privilege(
         'anon', 'public.rls_auto_enable()', 'EXECUTE'
       ) as anon_can_execute,
       has_function_privilege(
         'authenticated', 'public.rls_auto_enable()', 'EXECUTE'
       ) as authenticated_can_execute;

select e.evtname, e.evtevent, e.evtenabled, p.prosecdef
from pg_catalog.pg_event_trigger e
join pg_catalog.pg_proc p on p.oid = e.evtfoid
where e.evtfoid = to_regprocedure('public.rls_auto_enable()');

begin;
create table public.issue242_rls_release_probe (id bigint primary key);
select c.relrowsecurity
from pg_catalog.pg_class c
where c.oid = 'public.issue242_rls_release_probe'::regclass;
rollback;
```

기대값은 두 `has_function_privilege`가 모두 `false`, event trigger가 enabled이고
SECURITY DEFINER인 상태, probe의 `relrowsecurity=true`다. 이어서 모든 일반/partitioned public table에
RLS가 켜졌는지 확인한다.

```sql
select n.nspname, c.relname
from pg_catalog.pg_class c
join pg_catalog.pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relkind in ('r', 'p')
  and not c.relrowsecurity
order by c.relname;
```

위 결과는 0행이어야 한다. `profile-images`는 `storage.buckets.public=true`와 허용 MIME/크기 제한,
authenticated canonical owner-key INSERT/SELECT 정책, UPDATE/DELETE restrictive guard,
anon INSERT/SELECT restrictive guard를 catalog에서 재확인한다. anon/authenticated 실제 DB 세션으로
owner key 성공, 다른 owner·잘못된 key·UPDATE·DELETE·anon INSERT/SELECT 실패도 수행한다.

## Rollback

이 변경은 schema나 event trigger를 삭제하지 않는 ACL-only migration이므로 forward correction을
우선한다. 함수나 event trigger를 drop/recreate하지 않는다. 예기치 않은 영향이 확인되면 먼저
트래픽 변경을 중단하고 적용 전 `proacl`, event trigger catalog, migration history를 비교한다.

PUBLIC EXECUTE 복구는 anon/authenticated를 포함한 모든 역할에 SECURITY DEFINER RPC 호출을 다시
열어 security advisor 경고를 되살리므로 일반 rollback으로 사용하지 않는다. 불가피한 복구는 별도
Reviewer 승인, 정확한 project ref 재확인, 시간 제한 변경 창과 후속 즉시 회수 계획이 있을 때만
적용 전 ACL을 정확히 복원한다. 데이터 삭제, schema reset, seed 적재는 rollback 절차에 포함하지 않는다.
