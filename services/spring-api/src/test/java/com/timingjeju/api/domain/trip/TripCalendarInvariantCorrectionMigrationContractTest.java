package com.timingjeju.api.domain.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class TripCalendarInvariantCorrectionMigrationContractTest {
  private static final String MIGRATION =
      "20260909000000_trip_calendar_child_invariant_correction.sql";

  @Test
  void corrective_migration은_root변경에서도_event_exact경계와_preference_day를_mutex안에서_검사한다()
      throws Exception {
    String sql =
        Files.readString(repositoryRoot().resolve("supabase/migrations").resolve(MIGRATION));

    assertThat(sql)
        .contains("perform public.lock_trip_plan_schedule_mutex(new.id)")
        .contains("event.event_type = 'arrival'")
        .contains("event.event_type = 'departure'")
        .contains("preference.target_day_no")
        .contains("errcode = '23514'")
        .contains("constraint = 'ck_trip_calendar_children_match_root'")
        .contains("before update of start_date, end_date")
        .contains("on public.trip_plans");
  }

  @Test
  void 세_compose는_corrective_migration을_044로_한번씩_mount한다() throws Exception {
    Path root = repositoryRoot();
    String mount =
        "./supabase/migrations/"
            + MIGRATION
            + ":/docker-entrypoint-initdb.d/044_trip_calendar_child_invariant_correction.sql:ro";
    for (String compose : List.of("docker-compose.yml", "compose.yml", "compose.test.yml")) {
      assertThat(Files.readString(root.resolve(compose))).as(compose).containsOnlyOnce(mount);
    }
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("supabase/migrations"))) return current;
      current = current.getParent();
    }
    throw new AssertionError("repository root를 찾을 수 없습니다.");
  }
}
