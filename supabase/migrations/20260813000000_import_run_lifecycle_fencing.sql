-- Issue #22의 Spring import 생명주기가 stale writer를 거부하도록 실행 소유권과
-- fencing token을 data_import_runs provenance ledger에 추가한다.
alter table public.data_import_runs
  add column owner_token uuid default gen_random_uuid() not null,
  add column fencing_token bigint default 1 not null,
  add constraint chk_data_import_runs_fencing_token
    check (fencing_token > 0);

create function public.protect_import_run_write_lease()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if old.owner_token is distinct from new.owner_token
     or old.fencing_token is distinct from new.fencing_token then
    raise exception using
      errcode = '23514',
      message = 'import run write lease is immutable';
  end if;

  return new;
end;
$$;

create trigger trg_data_import_runs_write_lease_immutable
before update of owner_token, fencing_token
on public.data_import_runs
for each row execute function public.protect_import_run_write_lease();
