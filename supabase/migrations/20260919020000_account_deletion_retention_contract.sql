-- #106: keep legal consent evidence while removing its link to the deleted account.
alter table public.user_consents
  drop constraint user_consents_user_id_fkey;

alter table public.user_consents
  alter column user_id drop not null;

alter table public.user_consents
  add constraint user_consents_user_id_fkey
  foreign key (user_id) references public.user_profiles(id) on delete set null;

-- Assert that #61's request audit survives eventual Auth cascade/profile deletion.
do $$
begin
  if not exists (
    select 1
    from pg_constraint
    where conname = 'account_deletion_requests_user_profile_id_fkey'
      and confdeltype = 'n'
  ) then
    raise exception 'account_deletion_requests profile FK must remain ON DELETE SET NULL';
  end if;
end
$$;
