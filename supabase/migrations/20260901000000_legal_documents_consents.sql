alter table public.legal_documents
  add column locale text not null default 'ko-KR';

do $$
declare
  expected record;
begin
  for expected in
    select *
    from (values
      (
        '09200000-0000-0000-0000-000000000001'::uuid,
        'terms'::text,
        'ko-KR'::text,
        '1.0.0'::text,
        '서비스 이용약관'::text,
        'https://timing-jeju.example/legal/terms/1.0.0'::text,
        true,
        '2026-08-01T00:00:00+09:00'::timestamptz
      ),
      (
        '09200000-0000-0000-0000-000000000002'::uuid,
        'privacy'::text,
        'ko-KR'::text,
        '1.0.0'::text,
        '개인정보 처리방침'::text,
        'https://timing-jeju.example/legal/privacy/1.0.0'::text,
        true,
        '2026-08-01T00:00:00+09:00'::timestamptz
      ),
      (
        '09200000-0000-0000-0000-000000000003'::uuid,
        'location'::text,
        'ko-KR'::text,
        '2026-08-11.v1'::text,
        '위치기반서비스 이용약관'::text,
        'https://timing-jeju.example/legal/location/2026-08-11.v1'::text,
        true,
        '2026-08-11T00:00:00+09:00'::timestamptz
      )
    ) as seed(
      id, document_type, locale, version, title, content_url, required, effective_at
    )
  loop
    if exists (
      select 1
      from public.legal_documents existing
      where existing.id = expected.id
        and row(
          existing.document_type,
          existing.locale,
          existing.version,
          existing.title,
          existing.content_url,
          existing.required,
          existing.effective_at,
          existing.retired_at
        ) is distinct from row(
          expected.document_type,
          expected.locale,
          expected.version,
          expected.title,
          expected.content_url,
          expected.required,
          expected.effective_at,
          null::timestamptz
        )
    ) then
      raise exception using
        errcode = '23505',
        message = 'legal_document_seed_id_conflict';
    end if;

    if exists (
      select 1
      from public.legal_documents existing
      where existing.document_type = expected.document_type
        and existing.locale = expected.locale
        and existing.version = expected.version
        and existing.id <> expected.id
    ) then
      raise exception using
        errcode = '23505',
        message = 'legal_document_seed_natural_key_conflict';
    end if;
  end loop;
end
$$;

alter table public.legal_documents
  drop constraint legal_documents_document_type_version_key;

alter table public.legal_documents
  add constraint legal_documents_type_locale_version_key
  unique (document_type, locale, version);

alter table public.legal_documents
  add constraint legal_documents_locale_check
  check (locale ~ '^[a-z]{2}-[A-Z]{2}$');

insert into public.legal_documents (
  id, document_type, locale, version, title, content_url, required, effective_at
) values
  (
    '09200000-0000-0000-0000-000000000001',
    'terms',
    'ko-KR',
    '1.0.0',
    '서비스 이용약관',
    'https://timing-jeju.example/legal/terms/1.0.0',
    true,
    '2026-08-01T00:00:00+09:00'
  ),
  (
    '09200000-0000-0000-0000-000000000002',
    'privacy',
    'ko-KR',
    '1.0.0',
    '개인정보 처리방침',
    'https://timing-jeju.example/legal/privacy/1.0.0',
    true,
    '2026-08-01T00:00:00+09:00'
  ),
  (
    '09200000-0000-0000-0000-000000000003',
    'location',
    'ko-KR',
    '2026-08-11.v1',
    '위치기반서비스 이용약관',
    'https://timing-jeju.example/legal/location/2026-08-11.v1',
    true,
    '2026-08-11T00:00:00+09:00'
  )
on conflict (id) do nothing;

alter table public.legal_documents enable row level security;
alter table public.user_consents enable row level security;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on public.legal_documents from anon';
    execute 'revoke all on public.user_consents from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on public.legal_documents from authenticated';
    execute 'revoke all on public.user_consents from authenticated';
  end if;
end
$$;
