-- Synthetic legacy route with explicit trip/version/item and public-place lineage.
begin;
do $$
declare
  owner_id uuid := gen_random_uuid();
  trip_id uuid := gen_random_uuid();
  day_id uuid := gen_random_uuid();
  version_id uuid := gen_random_uuid();
  origin_place uuid := gen_random_uuid();
  destination_place uuid := gen_random_uuid();
  origin_item uuid := gen_random_uuid();
  destination_item uuid := gen_random_uuid();
  route_id uuid := gen_random_uuid();
begin
  insert into auth.users(id,email) values (owner_id,owner_id::text || '@issue225.test');
  insert into public.user_profiles(id,email) values (owner_id,owner_id::text || '@issue225.test');
  insert into public.tour_places(id,name,normalized_name,category,location,source_provider)
  values (origin_place,'공개 출발지','공개 출발지','ATTRACTION',ST_SetSRID(ST_MakePoint(126.5,33.5),4326)::geography,'fixture'),
         (destination_place,'공개 도착지','공개 도착지','ATTRACTION',ST_SetSRID(ST_MakePoint(126.501,33.501),4326)::geography,'fixture');
  insert into public.trip_plans(id,user_id,public_token,title,status,start_date,end_date,source_mode,data_version,revision)
  values (trip_id,owner_id,'issue225-legacy-route','합성 계획 경로','draft','2026-09-01','2026-09-01','fixture','issue225-v1',1);
  insert into public.trip_days(id,trip_plan_id,day_no,trip_date) values (day_id,trip_id,1,'2026-09-01');
  insert into public.trip_schedule_versions(id,trip_plan_id,version_no,status,source_type)
  values (version_id,trip_id,1,'draft','initial');
  insert into public.trip_items
    (id,trip_plan_id,trip_day_id,schedule_version_id,sequence_no,item_type,place_id,
     planned_start_at,planned_end_at,stay_minutes,source)
  values (origin_item,trip_id,day_id,version_id,1,'place_visit',origin_place,'2026-09-01T00:00:00Z','2026-09-01T01:00:00Z',60,'user_input'),
         (destination_item,trip_id,day_id,version_id,2,'place_visit',destination_place,'2026-09-01T03:00:00Z','2026-09-01T04:00:00Z',60,'user_input');
  insert into public.mobility_route_snapshots
    (id,request_hash,origin_location,destination_location,transport_mode,departure_at,
     distance_meters,duration_minutes,estimated_fare,source_provider,source_operation,route_summary,expires_at)
  select route_id,'synthetic-legacy-request-v0',origin.location,destination.location,'walk','2026-09-01T01:00:00Z',
         500,10,0,'fixture','route','{"walkMinutes":10,"waitMinutes":0,"rideMinutes":0,"transferMinutes":0}'::jsonb,now()+interval '1 hour'
  from public.tour_places origin,public.tour_places destination
  where origin.id=origin_place and destination.id=destination_place;
  insert into public.trip_legs
    (trip_plan_id,trip_day_id,schedule_version_id,sequence_no,from_item_id,to_item_id,transport_mode,
     mobility_route_snapshot_id,planned_departure_at,planned_arrival_at,walk_minutes,wait_minutes,
     ride_minutes,transfer_minutes,duration_minutes,buffer_minutes,distance_meters,estimated_fare)
  values (trip_id,day_id,version_id,1,origin_item,destination_item,'walk',route_id,
          '2026-09-01T01:00:00Z','2026-09-01T01:10:00Z',10,0,0,0,10,0,500,0);
  update public.trip_schedule_versions set status='active',applied_at=now() where id=version_id;
  update public.trip_plans set active_schedule_version_id=version_id,status='planned' where id=trip_id;
end;
$$;
commit;
