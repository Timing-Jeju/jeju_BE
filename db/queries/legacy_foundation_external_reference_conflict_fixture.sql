\set ON_ERROR_STOP on

-- .100에서는 valid_from이 다르면 같은 외부 코드의 겹치는 유효기간이 합법이다.
insert into public.external_reference_codes (
  id, source_provider, source_service, code_type, external_code, code_name,
  valid_from, valid_to
) values
(
  'e7100000-0000-0000-0000-000000000001',
  '한국관광공사', 'KorService2', 'lclsSystm1', 'A01', '자연',
  '2026-01-01', '2026-12-31'
),
(
  'e7100000-0000-0000-0000-000000000002',
  '한국관광공사', 'KorService2', 'lclsSystm1', 'A01', '자연(개정)',
  '2026-06-01', null
);
