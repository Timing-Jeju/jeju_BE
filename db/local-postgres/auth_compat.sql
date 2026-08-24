-- Supabase가 아닌 일반 PostgreSQL/PostGIS Docker 검증 전용 호환 계층이다.
-- 운영 Supabase 마이그레이션에는 이 파일을 적용하지 않는다.
create schema if not exists auth;

do $$
begin
  if not exists (select 1 from pg_roles where rolname = 'anon') then
    create role anon nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'authenticated') then
    create role authenticated nologin;
  end if;
  if not exists (select 1 from pg_roles where rolname = 'service_role') then
    create role service_role nologin bypassrls;
  end if;
exception
  when insufficient_privilege then
    null;
end $$;

create table if not exists auth.users (
  id uuid primary key default gen_random_uuid(),
  email text unique,
  raw_app_meta_data jsonb not null default '{}'::jsonb,
  raw_user_meta_data jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  last_sign_in_at timestamptz
);

-- 일반 PostgreSQL 통합 테스트에서 Supabase Auth identity의 읽기 계약만 재현한다.
-- 운영 Supabase의 auth 스키마에는 이 호환 객체를 적용하지 않는다.
create table if not exists auth.identities (
  id text primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  provider text not null,
  provider_id text not null,
  identity_data jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (provider, provider_id),
  unique (user_id, provider)
);

create or replace function public.create_local_test_user(target_user_id uuid, target_email text)
returns void
language sql
security invoker
set search_path = ''
as $$
  insert into auth.users (id, email)
  values (target_user_id, target_email)
$$;

revoke execute on function public.create_local_test_user(uuid, text) from public;

create or replace function auth.uid()
returns uuid
language sql
stable
as $$
  select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
$$;
