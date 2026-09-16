-- Issue #106: serialize cancellation with the first irreversible deletion step.
-- Additive and independent from Issue #61's later hardening migration.

alter table public.account_deletion_requests
  add column if not exists destructive_started_at timestamptz;

alter table public.account_deletion_requests
  add constraint account_deletion_cancel_before_destructive_check
  check (destructive_started_at is null or cancellation_requested = false);

comment on column public.account_deletion_requests.destructive_started_at is
  'First profile Storage deletion start marker; cancellation is forbidden after this CAS.';

create or replace function public.guard_account_deletion_irreversible_state()
returns trigger
language plpgsql
set search_path = pg_catalog, public
as $$
begin
  if old.destructive_started_at is not null
     and new.destructive_started_at is distinct from old.destructive_started_at then
    raise exception using errcode = '23514',
      message = 'account deletion destructive marker is immutable';
  end if;
  if old.cancellation_requested and not new.cancellation_requested then
    raise exception using errcode = '23514',
      message = 'account deletion cancellation is irreversible';
  end if;
  return new;
end
$$;

revoke all on function public.guard_account_deletion_irreversible_state() from public;

create trigger guard_account_deletion_irreversible_state
before update of destructive_started_at, cancellation_requested
on public.account_deletion_requests
for each row execute function public.guard_account_deletion_irreversible_state();
