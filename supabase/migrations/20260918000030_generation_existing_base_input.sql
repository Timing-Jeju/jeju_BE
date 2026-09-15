-- #53: Day 1 accepts an existing manual base; later days require a base.
begin;

create or replace function timing_jeju_planner_private.generation_trip_input_valid(j jsonb)
returns boolean language plpgsql immutable security invoker set search_path=''
as $$
declare item jsonb; boundary jsonb; field text; target jsonb; preference jsonb;
  day_count integer; day_index integer; target_no integer; expected_place_count integer;
  start_place jsonb; end_place jsonb;
begin
  if not timing_jeju_planner_private.generation_input_object(j,array[
    'tripId','tripRevision','baseScheduleVersionId','boundary','airportPlaceId','days','dayAnchors',
    'savedPreferences','transportModes','preferredCategories','relaxedPace','places'])
    or not timing_jeju_planner_private.generation_input_uuid(j->'tripId')
    or not timing_jeju_planner_private.generation_input_uuid(j->'airportPlaceId')
    or not (j->'baseScheduleVersionId'='null'::jsonb or
      timing_jeju_planner_private.generation_input_uuid(j->'baseScheduleVersionId'))
    or jsonb_typeof(j->'tripRevision')<>'number' or (j->>'tripRevision') !~ '^[1-9][0-9]*$'
    or (j->>'tripRevision')::bigint<1 or jsonb_typeof(j->'relaxedPace')<>'boolean'
    then return false; end if;
  foreach field in array array['days','dayAnchors','savedPreferences','transportModes','preferredCategories','places'] loop
    if jsonb_typeof(j->field)<>'array' or jsonb_array_length(j->field)>1000 then return false; end if;
  end loop;
  if jsonb_array_length(j->'days') not between 1 and 5
    or jsonb_array_length(j->'dayAnchors')>5
    or jsonb_array_length(j->'transportModes') not between 1 and 3
    or jsonb_array_length(j->'preferredCategories')>4 then return false; end if;
  for item in select value from jsonb_array_elements(j->'transportModes') loop
    if item not in ('"bus"'::jsonb,'"taxi"'::jsonb,'"walk"'::jsonb) then return false; end if;
  end loop;
  for item in select value from jsonb_array_elements(j->'preferredCategories') loop
    if item not in ('"restaurant"'::jsonb,'"cafe"'::jsonb,'"leisure"'::jsonb,'"cultural_facility"'::jsonb) then return false; end if;
  end loop;
  for item in select value from jsonb_array_elements(j->'days') loop
    if not timing_jeju_planner_private.generation_input_object(item,array['dayId','dayNo','date','activityStartTime','activityEndTime'])
      or not timing_jeju_planner_private.generation_input_uuid(item->'dayId')
      or jsonb_typeof(item->'dayNo')<>'number' or (item->>'dayNo') !~ '^[1-5]$'
      or jsonb_typeof(item->'date')<>'string' or (item->>'date') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
      then return false; end if;
    perform (item->>'date')::date;
    foreach field in array array['activityStartTime','activityEndTime'] loop
      if jsonb_typeof(item->field)<>'string' or (item->>field) !~ '^([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\.[0-9]{1,9})?$' then return false; end if;
    end loop;
    if (item->>'activityStartTime')::time >= (item->>'activityEndTime')::time then return false; end if;
  end loop;
  for item in select value from jsonb_array_elements(j->'dayAnchors') loop
    if not timing_jeju_planner_private.generation_input_object(item,array['dayId','lodgingPlaceId'])
      or not timing_jeju_planner_private.generation_input_uuid(item->'dayId')
      or not timing_jeju_planner_private.generation_input_uuid(item->'lodgingPlaceId') then return false; end if;
  end loop;
  boundary:=j->'boundary';
  if not timing_jeju_planner_private.generation_input_object(boundary,array['dayId','dayNo','startPlaceId','endPlaceId','startAt','endAt'])
    or not timing_jeju_planner_private.generation_input_uuid(boundary->'dayId')
    or not timing_jeju_planner_private.generation_input_uuid(boundary->'startPlaceId')
    or not timing_jeju_planner_private.generation_input_uuid(boundary->'endPlaceId')
    or jsonb_typeof(boundary->'dayNo')<>'number' or (boundary->>'dayNo') !~ '^[1-5]$' then return false; end if;
  foreach field in array array['startAt','endAt'] loop
    if jsonb_typeof(boundary->field)<>'string' or (boundary->>field) !~
      '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-2][0-9]:[0-5][0-9]:[0-5][0-9](\.[0-9]{1,9})?\+09:00$' then return false; end if;
  end loop;
  if (boundary->>'startAt')::timestamptz >= (boundary->>'endAt')::timestamptz then return false; end if;
  for item in select value from jsonb_array_elements(j->'savedPreferences') loop
    if not timing_jeju_planner_private.generation_input_object(item,array['placeId','type','targetDayNo','priority','requestedStayMinutes'])
      or not timing_jeju_planner_private.generation_input_uuid(item->'placeId')
      or item->'type' not in ('"must_visit"'::jsonb,'"preferred"'::jsonb,'"avoid"'::jsonb)
      or not (item->'targetDayNo'='null'::jsonb or (jsonb_typeof(item->'targetDayNo')='number' and (item->>'targetDayNo') ~ '^[1-5]$'))
      or jsonb_typeof(item->'priority')<>'number' or (item->>'priority') !~ '^[0-9]{1,3}$'
      or (item->>'priority')::integer not between 0 and 100 then return false; end if;
    if item->'requestedStayMinutes'<>'null'::jsonb then
      if jsonb_typeof(item->'requestedStayMinutes')<>'number' or (item->>'requestedStayMinutes') !~ '^[0-9]{1,4}$'
        or (item->>'requestedStayMinutes')::integer not between 1 and 1440 then return false; end if;
    end if;
  end loop;
  for item in select value from jsonb_array_elements(j->'places') loop
    if not timing_jeju_planner_private.generation_input_object(item,array['placeId','type','priority','stayMinutes','staySource','stayPolicyVersion','stayPolicyEffectiveAt'])
      or not timing_jeju_planner_private.generation_input_uuid(item->'placeId')
      or item->'type' not in ('"must_visit"'::jsonb,'"preferred"'::jsonb,'"avoid"'::jsonb)
      or jsonb_typeof(item->'priority')<>'number' or (item->>'priority') !~ '^[0-9]{1,3}$'
      or (item->>'priority')::integer not between 0 and 100 then return false; end if;
    if item->>'type'='avoid' then
      foreach field in array array['stayMinutes','staySource','stayPolicyVersion','stayPolicyEffectiveAt'] loop
        if item->field<>'null'::jsonb then return false; end if;
      end loop;
    else
      if jsonb_typeof(item->'stayMinutes')<>'number' or (item->>'stayMinutes') !~ '^[0-9]{1,4}$'
        or (item->>'stayMinutes')::integer not between 1 and 1440 then return false; end if;
      if item->>'staySource'='user_requested' then
        if item->'stayPolicyVersion'<>'null'::jsonb or item->'stayPolicyEffectiveAt'<>'null'::jsonb then return false; end if;
      elsif item->>'staySource' in ('place_override','category_default') then
        if jsonb_typeof(item->'stayPolicyVersion')<>'string' or (item->>'stayPolicyVersion') !~ '^[a-z0-9][a-z0-9._-]{0,63}$'
          or jsonb_typeof(item->'stayPolicyEffectiveAt')<>'string' or (item->>'stayPolicyEffectiveAt') !~
          '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-2][0-9]:[0-5][0-9]:[0-5][0-9](\.[0-9]{1,9})?Z$' then return false; end if;
        perform (item->>'stayPolicyEffectiveAt')::timestamptz;
      else return false; end if;
    end if;
  end loop;
  -- 복원에도 동일한 Day/장소 의미 검증을 적용한다. 해시 일치만으로 유효성을 추론하지 않는다.
  day_count:=jsonb_array_length(j->'days');
  target_no:=(boundary->>'dayNo')::integer;
  if target_no>day_count or (target_no>1 and j->'baseScheduleVersionId'='null'::jsonb)
    or exists (select 1 from jsonb_array_elements(j->'days') d group by d->>'dayId' having count(*)>1)
    or exists (select 1 from jsonb_array_elements(j->'dayAnchors') a group by a->>'dayId' having count(*)>1)
    or exists (select 1 from jsonb_array_elements(j->'savedPreferences') p group by p->>'placeId' having count(*)>1)
    or exists (select 1 from jsonb_array_elements(j->'places') p group by p->>'placeId' having count(*)>1)
    or exists (select 1 from jsonb_array_elements(j->'transportModes') m group by m having count(*)>1)
    or exists (select 1 from jsonb_array_elements(j->'preferredCategories') c group by c having count(*)>1)
    then return false; end if;
  for day_index in 0..day_count-1 loop
    item:=j->'days'->day_index;
    if (item->>'dayNo')::integer<>day_index+1
      or (item->>'date')::date<>((j->'days'->0->>'date')::date+day_index) then return false; end if;
    if day_index<day_count-1 and not exists (select 1 from jsonb_array_elements(j->'dayAnchors') a
      where a->'dayId'=item->'dayId') then return false; end if;
  end loop;
  if exists (select 1 from jsonb_array_elements(j->'dayAnchors') a where not exists
    (select 1 from jsonb_array_elements(j->'days') d where d->'dayId'=a->'dayId')) then return false; end if;
  target:=j->'days'->(target_no-1);
  if boundary->'dayId' is distinct from target->'dayId'
    or left(boundary->>'startAt',10)<>target->>'date' or left(boundary->>'endAt',10)<>target->>'date'
    or (boundary->>'startAt')::timestamptz < (((target->>'date')::date+(target->>'activityStartTime')::time) at time zone 'Asia/Seoul')
    or (boundary->>'endAt')::timestamptz > (((target->>'date')::date+(target->>'activityEndTime')::time) at time zone 'Asia/Seoul') then return false; end if;
  if target_no=1 then start_place:=j->'airportPlaceId';
  else select a->'lodgingPlaceId' into start_place from jsonb_array_elements(j->'dayAnchors') a
    where a->'dayId'=j->'days'->(target_no-2)->'dayId'; end if;
  if target_no=day_count then end_place:=j->'airportPlaceId';
  else select a->'lodgingPlaceId' into end_place from jsonb_array_elements(j->'dayAnchors') a
    where a->'dayId'=target->'dayId'; end if;
  if boundary->'startPlaceId' is distinct from start_place or boundary->'endPlaceId' is distinct from end_place then return false; end if;
  expected_place_count:=0;
  for preference in select value from jsonb_array_elements(j->'savedPreferences') loop
    if preference->'targetDayNo'<>'null'::jsonb and (preference->>'targetDayNo')::integer>day_count then return false; end if;
    if preference->'targetDayNo'='null'::jsonb or (preference->>'targetDayNo')::integer=target_no then
      expected_place_count:=expected_place_count+1;
      select value into item from jsonb_array_elements(j->'places') p(value) where value->'placeId'=preference->'placeId';
      if item is null or item->'type'<>preference->'type' or item->'priority'<>preference->'priority' then return false; end if;
      if preference->>'type'<>'avoid' then
        if preference->'requestedStayMinutes'='null'::jsonb then
          if item->>'staySource'='user_requested' then return false; end if;
        elsif item->'stayMinutes'<>preference->'requestedStayMinutes' or item->>'staySource'<>'user_requested' then return false; end if;
      end if;
    end if;
  end loop;
  if expected_place_count<>jsonb_array_length(j->'places')
    or (select count(*) from jsonb_array_elements(j->'places') p where p->>'type'='must_visit')>10
    or (select count(*) from jsonb_array_elements(j->'places') p where p->>'type'='preferred')>30
    or not exists (select 1 from jsonb_array_elements(j->'places') p where p->>'type'<>'avoid') then return false; end if;
  return true;
exception when data_exception then return false;
end;
$$;

revoke all on function timing_jeju_planner_private.generation_trip_input_valid(jsonb) from public, anon, authenticated;
grant execute on function timing_jeju_planner_private.generation_trip_input_valid(jsonb) to service_role;

commit;
