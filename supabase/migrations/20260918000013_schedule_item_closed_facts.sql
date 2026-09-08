-- Issue #225: close the arbitrary item facts slot before planned-anchor lineage changes.
begin;

lock table public.trip_items in access exclusive mode;

do $$
begin
  if exists (select 1 from public.trip_items where facts is distinct from '{}'::jsonb) then
    raise exception using
      errcode = '23514',
      message = 'legacy schedule item facts require provenance audit';
  end if;
end;
$$;

-- Item metadata has no approved free-form fields. Evidence belongs to versioned
-- normalized projections; never persist arbitrary input or coordinates in this slot.
create or replace function timing_jeju_private.validate_trip_item_closed_facts()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if new.facts is distinct from '{}'::jsonb then
    raise exception using
      errcode = '23514',
      message = 'schedule item facts must satisfy the closed non-location contract';
  end if;
  return new;
end;
$$;

revoke all on function timing_jeju_private.validate_trip_item_closed_facts()
  from public, anon, authenticated, service_role;

create trigger trg_trip_items_closed_facts
before insert or update of facts on public.trip_items
for each row execute function timing_jeju_private.validate_trip_item_closed_facts();

alter table public.trip_items add constraint chk_trip_items_closed_facts
  check (facts = '{}'::jsonb);

commit;
