-- Issue #47: canonical trip arrival/departure transport-event mutation contract.
do $$
begin
  if exists (
    select 1
    from public.trip_transport_events event
    where num_nonnulls(event.terminal_place_id, event.terminal_name) <> 1
       or (
         event.terminal_name is not null
         and (
           event.terminal_name <> btrim(event.terminal_name)
           or event.terminal_name <> normalize(event.terminal_name, NFC)
           or char_length(event.terminal_name) not between 1 and 100
           or event.terminal_name ~ '[[:cntrl:]]'
         )
       )
       or (
         event.transport_number is not null
         and (
           event.transport_number <> btrim(event.transport_number)
           or event.transport_number <> normalize(event.transport_number, NFC)
           or char_length(event.transport_number) not between 1 and 30
           or event.transport_number ~ '[[:cntrl:]]'
         )
       )
       or (
         event.note is not null
         and (
           event.note <> btrim(event.note)
           or event.note <> normalize(event.note, NFC)
           or char_length(event.note) not between 1 and 500
           or event.note ~ '[[:cntrl:]]'
         )
       )
       or timezone('Asia/Seoul', event.scheduled_at)::date <>
          case event.event_type
            when 'arrival' then (
              select trip.start_date from public.trip_plans trip where trip.id = event.trip_plan_id
            )
            when 'departure' then (
              select trip.end_date from public.trip_plans trip where trip.id = event.trip_plan_id
            )
          end
  ) then
    raise exception 'legacy transport event contract conflict';
  end if;
end;
$$;

do $$
declare
  existing_constraint record;
begin
  for existing_constraint in
    select constraint_row.conname
    from pg_constraint constraint_row
    where constraint_row.conrelid = 'public.trip_transport_events'::regclass
      and constraint_row.contype = 'c'
      and pg_get_constraintdef(constraint_row.oid) ilike '%terminal_place_id%'
      and pg_get_constraintdef(constraint_row.oid) ilike '%terminal_name%'
  loop
    execute format(
      'alter table public.trip_transport_events drop constraint %I',
      existing_constraint.conname
    );
  end loop;
end;
$$;

alter table public.trip_transport_events
  add constraint ck_trip_transport_events_exactly_one_terminal
    check (num_nonnulls(terminal_place_id, terminal_name) = 1),
  add constraint ck_trip_transport_events_terminal_name_canonical
    check (
      terminal_name is null or (
        terminal_name = btrim(terminal_name)
        and terminal_name = normalize(terminal_name, NFC)
        and char_length(terminal_name) between 1 and 100
        and terminal_name !~ '[[:cntrl:]]'
      )
    ),
  add constraint ck_trip_transport_events_transport_number_canonical
    check (
      transport_number is null or (
        transport_number = btrim(transport_number)
        and transport_number = normalize(transport_number, NFC)
        and char_length(transport_number) between 1 and 30
        and transport_number !~ '[[:cntrl:]]'
      )
    ),
  add constraint ck_trip_transport_events_note_canonical
    check (
      note is null or (
        note = btrim(note)
        and note = normalize(note, NFC)
        and char_length(note) between 1 and 500
        and note !~ '[[:cntrl:]]'
      )
    );

create or replace function public.validate_trip_calendar_child()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
declare
  plan_start_date date;
  plan_end_date date;
  event_local_date date;
begin
  perform public.lock_trip_plan_schedule_mutex(new.trip_plan_id);

  select plan.start_date, plan.end_date
    into plan_start_date, plan_end_date
  from public.trip_plans plan
  where plan.id = new.trip_plan_id;

  if not found then
    raise exception 'trip plan % does not exist', new.trip_plan_id;
  end if;

  if tg_table_name = 'trip_days' then
    if new.trip_date <> plan_start_date + (new.day_no - 1)
       or new.trip_date > plan_end_date then
      raise exception 'trip day date is inconsistent with trip range';
    end if;
  elsif tg_table_name = 'trip_transport_events' then
    event_local_date := timezone('Asia/Seoul', new.scheduled_at)::date;
    if (new.event_type = 'arrival' and event_local_date <> plan_start_date)
       or (new.event_type = 'departure' and event_local_date <> plan_end_date) then
      raise exception 'transport event date is inconsistent with event boundary';
    end if;
  elsif tg_table_name = 'trip_accommodations' then
    if new.check_in_date < plan_start_date
       or new.check_out_date > plan_end_date
       or new.check_out_date <= new.check_in_date then
      raise exception 'accommodation range is outside trip range';
    end if;
  end if;

  return new;
end;
$$;

do $$
begin
  if exists (select 1 from pg_roles where rolname = 'anon') then
    execute 'revoke all on public.trip_transport_events from anon';
  end if;
  if exists (select 1 from pg_roles where rolname = 'authenticated') then
    execute 'revoke all on public.trip_transport_events from authenticated';
  end if;
  if exists (select 1 from pg_roles where rolname = 'service_role') then
    execute 'revoke all on public.trip_transport_events from service_role';
    execute 'grant select,insert,update,delete on public.trip_transport_events to service_role';
  end if;
end;
$$;
