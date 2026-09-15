-- Issue #261: preserve DB020's closed JSON guard while allowing one reviewed,
-- non-location generation projection. No legacy migration is edited or replayed.
begin;

create function timing_jeju_private.user_generation_leg_facts_v1_matches_write_contract(
  input_value jsonb
) returns boolean language plpgsql stable strict security invoker set search_path = ''
as $$
declare
  generation jsonb;
  event_value jsonb;
  risk_value jsonb;
  reason_value jsonb;
  precision_value jsonb;
  key_name text;
begin
  if jsonb_typeof(input_value) <> 'object'
     or (select count(*) from jsonb_object_keys(input_value)) <> 1
     or not input_value ? 'generation' then return false; end if;
  generation := input_value -> 'generation';
  if jsonb_typeof(generation) <> 'object'
     or not generation ?& array['schemaVersion', 'events', 'risks']
     or (select count(*) from jsonb_object_keys(generation)) not in (3, 4)
     or exists (select 1 from jsonb_object_keys(generation) key
                where key not in ('schemaVersion', 'events', 'risks', 'precision'))
     or generation -> 'schemaVersion' <> '1'::jsonb
     or jsonb_typeof(generation -> 'events') <> 'array'
     or jsonb_typeof(generation -> 'risks') <> 'array'
     then return false; end if;
  if jsonb_array_length(generation -> 'events') > 256
     or jsonb_array_length(generation -> 'risks') > 256 then return false; end if;
  for event_value in select value from jsonb_array_elements(generation -> 'events') value loop
    if jsonb_typeof(event_value) <> 'object'
       or not event_value ?& array['type', 'durationMinutes']
       or (select count(*) from jsonb_object_keys(event_value)) <> 2
       or jsonb_typeof(event_value -> 'type') <> 'string'
       or event_value ->> 'type' not in ('buffer', 'transfer')
       or jsonb_typeof(event_value -> 'durationMinutes') <> 'number'
       or event_value ->> 'durationMinutes' !~ '^(0|[1-9][0-9]{0,3})$'
       then return false; end if;
    if (event_value ->> 'durationMinutes')::integer > 1440 then return false; end if;
  end loop;
  for risk_value in select value from jsonb_array_elements(generation -> 'risks') value loop
    if jsonb_typeof(risk_value) <> 'object'
       or not risk_value ?& array['level', 'reasonCodes']
       or (select count(*) from jsonb_object_keys(risk_value)) <> 2
       or jsonb_typeof(risk_value -> 'level') <> 'string'
       or risk_value ->> 'level' not in ('low', 'medium', 'high', 'critical', 'unknown')
       or jsonb_typeof(risk_value -> 'reasonCodes') <> 'array'
       then return false; end if;
    if jsonb_array_length(risk_value -> 'reasonCodes') > 32 then return false; end if;
    for reason_value in select value from jsonb_array_elements(risk_value -> 'reasonCodes') value loop
      if jsonb_typeof(reason_value) <> 'string'
         or reason_value #>> '{}' !~ '^[A-Z][A-Z0-9_]{0,127}$'
         or reason_value #>> '{}' ~ '(GPS|GEO|LOCATION|COORD|LATITUDE|LONGITUDE|GEOHASH)'
         then return false; end if;
    end loop;
  end loop;
  if generation ? 'precision' then
    precision_value := generation -> 'precision';
    if jsonb_typeof(precision_value) <> 'object'
       or not precision_value ?& array[
         'walkNanos', 'rideNanos', 'transferNanos', 'waitNanos', 'roundingNanos']
       or (select count(*) from jsonb_object_keys(precision_value)) <> 5
       then return false; end if;
    for key_name in select key from jsonb_object_keys(precision_value) key loop
      if jsonb_typeof(precision_value -> key_name) <> 'number'
         or precision_value ->> key_name !~ '^(0|[1-9][0-9]{0,13})$'
         then return false; end if;
      if (precision_value ->> key_name)::bigint > 86400000000000 then return false; end if;
    end loop;
    if (precision_value ->> 'roundingNanos')::bigint >= 60000000000
       then return false; end if;
  end if;
  return true;
end;
$$;
revoke all on function timing_jeju_private.user_generation_leg_facts_v1_matches_write_contract(jsonb)
  from public, anon, authenticated, service_role;

create or replace function timing_jeju_private.user_json_matches_write_contract(
  input_surface text, input_value jsonb
) returns boolean language sql stable strict security invoker set search_path = ''
as $$
  select case input_surface
    when 'trip_preferences.raw_answers' then
      jsonb_typeof(input_value) = 'object'
      and not exists (
        select 1 from jsonb_object_keys(input_value) key
        where key not in ('pace', 'partySize', 'childAges', 'generationMode', 'dayGeneration')
      )
      and (not input_value ? 'pace' or input_value ->> 'pace' in ('slow', 'normal', 'fast'))
      and (not input_value ? 'generationMode' or (
        jsonb_typeof(input_value -> 'generationMode') = 'string'
        and input_value ->> 'generationMode' = 'structured'
      ))
      and (not input_value ? 'dayGeneration' or (
        jsonb_typeof(input_value -> 'dayGeneration') = 'string'
        and input_value ->> 'dayGeneration' = 'one_click'
      ))
      and (not input_value ? 'partySize' or (
        jsonb_typeof(input_value -> 'partySize') = 'number'
        and input_value ->> 'partySize' ~ '^([1-9]|1[0-9]|20)$'
      ))
      and (not input_value ? 'childAges' or (
        jsonb_typeof(input_value -> 'childAges') = 'array'
        and jsonb_array_length(input_value -> 'childAges') <= 20
        and not exists (
          select 1 from jsonb_array_elements(input_value -> 'childAges') element
          where jsonb_typeof(element) <> 'number'
             or element #>> '{}' !~ '^(0|[1-9]|1[0-7])$'
        )
      ))
    when 'trip_items.facts' then input_value = '{}'::jsonb
    when 'trip_legs.facts' then
      input_value = '{}'::jsonb or (
        jsonb_typeof(input_value) = 'object'
        and not exists (
          select 1 from jsonb_object_keys(input_value) key where key <> 'derivation'
        )
        and input_value ->> 'derivation' = 'conservative_walk_v1'
      ) or (
        jsonb_typeof(input_value) = 'object'
        and input_value ? 'routeNo'
        and not exists (
          select 1 from jsonb_object_keys(input_value) key
          where key not in ('routeNo', 'lowFrequency')
        )
        and jsonb_typeof(input_value -> 'routeNo') = 'string'
        and input_value ->> 'routeNo' ~ '^[0-9]{1,10}$'
        and (not input_value ? 'lowFrequency'
          or jsonb_typeof(input_value -> 'lowFrequency') = 'boolean')
      ) or input_value in (
        '{"candidate":true}'::jsonb,
        '{"recovery":true}'::jsonb,
        '{"derivation":"same_place_continuity_v1"}'::jsonb)
        or timing_jeju_private.user_generation_leg_facts_v1_matches_write_contract(input_value)
    when 'itinerary_generation_runs.structured_input' then
      jsonb_typeof(input_value) = 'object'
      and input_value ?& array['targetDayId', 'candidateCount', 'refreshExternalFacts']
      and not exists (
        select 1 from jsonb_object_keys(input_value) key
        where key not in ('targetDayId', 'candidateCount', 'refreshExternalFacts')
      )
      and jsonb_typeof(input_value -> 'targetDayId') = 'string'
      and input_value ->> 'targetDayId'
        ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      and jsonb_typeof(input_value -> 'candidateCount') = 'number'
      and input_value ->> 'candidateCount' ~ '^([1-9]|10)$'
      and jsonb_typeof(input_value -> 'refreshExternalFacts') = 'boolean'
    when 'compute_runs.result_summary' then
      (jsonb_typeof(input_value) = 'object'
        and not exists (
          select 1 from jsonb_object_keys(input_value) key
          where key not in ('score', 'observedAt', 'expiresAt')
        )
        and (not input_value ? 'score' or (
          jsonb_typeof(input_value -> 'score') = 'number'
          and input_value ->> 'score' ~ '^([0-9]|[1-9][0-9]|100)$'
        ))
        and (not input_value ? 'observedAt' or (
          jsonb_typeof(input_value -> 'observedAt') = 'string'
          and input_value ->> 'observedAt'
            ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})$'
          and pg_catalog.pg_input_is_valid(input_value ->> 'observedAt', 'pg_catalog.timestamptz')
        ))
        and (not input_value ? 'expiresAt' or (
          jsonb_typeof(input_value -> 'expiresAt') = 'string'
          and input_value ->> 'expiresAt'
            ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})$'
          and pg_catalog.pg_input_is_valid(input_value ->> 'expiresAt', 'pg_catalog.timestamptz')
        ))) or (
          jsonb_typeof(input_value) = 'object'
          and input_value ?& array['overallStatus', 'score']
          and not exists (
            select 1 from jsonb_object_keys(input_value) key
            where key not in ('overallStatus', 'score')
          )
          and input_value ->> 'overallStatus' in ('ok', 'caution', 'blocked')
          and jsonb_typeof(input_value -> 'score') = 'number'
          and input_value ->> 'score' ~ '^([0-9]|[1-9][0-9]|100)$'
        ) or (
          jsonb_typeof(input_value) = 'object'
          and input_value ?& array['optionCount', 'bestScore']
          and not exists (
            select 1 from jsonb_object_keys(input_value) key
            where key not in ('optionCount', 'bestScore')
          )
          and jsonb_typeof(input_value -> 'optionCount') = 'number'
          and input_value ->> 'optionCount' ~ '^([1-9]|1[0-9]|20)$'
          and jsonb_typeof(input_value -> 'bestScore') = 'number'
          and input_value ->> 'bestScore' ~ '^([0-9]|[1-9][0-9]|100)$'
        )
    when 'ai_messages.structured_payload' then input_value = '{}'::jsonb
    when 'risk_events.computed_facts' then input_value = '{}'::jsonb or (
      jsonb_typeof(input_value) = 'object'
      and input_value ?& array['routeNo', 'missedBusWaitMinutes']
      and (select count(*) from jsonb_object_keys(input_value)) = 2
      and jsonb_typeof(input_value -> 'routeNo') = 'string'
      and input_value ->> 'routeNo' ~ '^[0-9]{1,10}$'
      and jsonb_typeof(input_value -> 'missedBusWaitMinutes') = 'number'
      and input_value ->> 'missedBusWaitMinutes' ~ '^(0|[1-9][0-9]{0,3})$'
    )
    when 'trip_weather_impacts.computed_facts' then input_value = '{}'::jsonb or (
      jsonb_typeof(input_value) = 'object'
      and input_value ?& array['precipitationProbabilityPercent', 'precipitationAmountMm']
      and (select count(*) from jsonb_object_keys(input_value)) = 2
      and jsonb_typeof(input_value -> 'precipitationProbabilityPercent') = 'number'
      and input_value ->> 'precipitationProbabilityPercent' ~ '^([0-9]|[1-9][0-9]|100)$'
      and jsonb_typeof(input_value -> 'precipitationAmountMm') = 'number'
      and input_value ->> 'precipitationAmountMm' ~ '^(0|[1-9][0-9]{0,3})(\.[0-9]+)?$'
    )
    when 'recommendation_candidates.facts' then input_value = '{}'::jsonb or (
      jsonb_typeof(input_value) = 'object'
      and (select array_agg(key order by key) from jsonb_object_keys(input_value) key)
        = array['distanceMeters']
      and jsonb_typeof(input_value -> 'distanceMeters') = 'number'
      and input_value ->> 'distanceMeters' ~ '^(0|[1-9][0-9]{0,8})$'
    )
    when 'recovery_options.change_summary' then input_value = '{}'::jsonb or (
      jsonb_typeof(input_value) = 'object'
      and input_value ?& array['changedItemCount', 'preservedRequiredItems']
      and (select count(*) from jsonb_object_keys(input_value)) = 2
      and jsonb_typeof(input_value -> 'changedItemCount') = 'number'
      and input_value ->> 'changedItemCount' ~ '^(0|[1-9][0-9]{0,3})$'
      and jsonb_typeof(input_value -> 'preservedRequiredItems') = 'boolean'
    )
    when 'recovery_option_changes.before_value' then
      jsonb_typeof(input_value) = 'object'
      and input_value ?& array['dayNo', 'startTime']
      and (select count(*) from jsonb_object_keys(input_value)) = 2
      and jsonb_typeof(input_value -> 'dayNo') = 'number'
      and input_value ->> 'dayNo' ~ '^([1-9]|[1-9][0-9])$'
      and jsonb_typeof(input_value -> 'startTime') = 'string'
      and input_value ->> 'startTime' ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
    when 'recovery_option_changes.after_value' then
      jsonb_typeof(input_value) = 'object'
      and input_value ?& array['dayNo', 'startTime']
      and (select count(*) from jsonb_object_keys(input_value)) = 2
      and jsonb_typeof(input_value -> 'dayNo') = 'number'
      and input_value ->> 'dayNo' ~ '^([1-9]|[1-9][0-9])$'
      and jsonb_typeof(input_value -> 'startTime') = 'string'
      and input_value ->> 'startTime' ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
    else false
  end;
$$;
revoke all on function timing_jeju_private.user_json_matches_write_contract(text, jsonb)
  from public, anon, authenticated, service_role;


commit;
