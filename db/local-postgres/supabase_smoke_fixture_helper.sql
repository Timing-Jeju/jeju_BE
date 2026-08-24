-- 로컬 Supabase smoke의 DB 음수 계약에서만 사용하는 수명 제한 fixture helper다.
-- 운영 Supabase migration에 포함하거나 배포하지 않는다.
begin;

create function public.create_local_test_user(target_user_id uuid, target_email text)
returns void
language sql
security invoker
set search_path = ''
as $$
  insert into auth.users (id, email)
  values (target_user_id, target_email)
$$;

revoke execute on function public.create_local_test_user(uuid, text) from public;
grant execute on function public.create_local_test_user(uuid, text) to supabase_admin;

commit;
