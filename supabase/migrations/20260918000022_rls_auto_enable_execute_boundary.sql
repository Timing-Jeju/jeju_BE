-- SECURITY DEFINER event-trigger 함수의 직접 실행은 migration owner에만 허용한다.
-- Event trigger 자체와 함수 본문은 변경하지 않는다.
revoke all on function public.rls_auto_enable() from public;
revoke execute on function public.rls_auto_enable() from anon;
revoke execute on function public.rls_auto_enable() from authenticated;
