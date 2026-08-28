drop policy if exists push_devices_owner_insert on public.push_devices;
drop policy if exists push_devices_owner_update on public.push_devices;
drop policy if exists notification_preferences_owner_insert
  on public.notification_preferences;
drop policy if exists notification_preferences_owner_update
  on public.notification_preferences;

revoke insert, update, delete on public.push_devices from authenticated;
revoke insert, update, delete on public.notification_preferences from authenticated;

grant select (
  id, user_id, device_id, platform, permission_status, app_version, locale, time_zone,
  last_seen_at, invalidated_at, created_at, updated_at
) on public.push_devices to authenticated;
grant select on public.notification_preferences to authenticated;

grant select, insert, update, delete on public.push_devices to service_role;
grant select, insert, update, delete on public.notification_preferences to service_role;
revoke truncate on public.push_devices from service_role;
revoke truncate on public.notification_preferences from service_role;
