package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripPlacePreferencesMigrationContractTest {
  private static final Path MIGRATION =
      Path.of("../../supabase/migrations/20260908000000_trip_place_preference_contract.sql");

  @Test
  void migration은_legacy_fail_closed와_장소별_단일_role_priority_Day_mutex를_강제한다() throws Exception {
    String sql = Files.readString(MIGRATION);
    String calendarValidation =
        sql.substring(
            sql.indexOf(
                "create or replace function public.validate_trip_place_preference_calendar_change"),
            sql.indexOf("create trigger trg_trip_place_preference_calendar_change"));

    assertThat(sql)
        .contains("legacy trip place preference contract conflict")
        .contains("primary key (trip_plan_id, place_id)")
        .contains("priority between 0 and 100")
        .contains("validate_trip_place_preference_contract")
        .contains("validate_trip_place_preference_calendar_change")
        .contains("lock_trip_plan_schedule_mutex")
        .contains("target_day_no")
        .contains("alter table public.trip_place_preferences enable row level security")
        .contains("drop policy if exists trip_place_preferences_owner_select")
        .contains(
            "revoke all on table public.trip_place_preferences from public, anon, authenticated")
        .contains("revoke all on function public.validate_trip_place_preference_contract()")
        .contains("revoke all on function public.validate_trip_place_preference_calendar_change()")
        .contains(
            "grant select, insert, update, delete on table public.trip_place_preferences to service_role");
    assertThat(calendarValidation).doesNotContain("lock_trip_plan_schedule_mutex");
  }
}
