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

    assertThat(sql)
        .contains("legacy trip place preference contract conflict")
        .contains("primary key (trip_plan_id, place_id)")
        .contains("priority between 0 and 100")
        .contains("validate_trip_place_preference_contract")
        .contains("validate_trip_place_preference_calendar_change")
        .contains("lock_trip_plan_schedule_mutex")
        .contains("target_day_no")
        .contains("revoke all on table public.trip_place_preferences from anon, authenticated")
        .contains(
            "grant select, insert, update, delete on table public.trip_place_preferences to service_role");
  }
}
