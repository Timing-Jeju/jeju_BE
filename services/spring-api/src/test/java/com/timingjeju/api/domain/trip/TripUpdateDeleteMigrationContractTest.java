package com.timingjeju.api.domain.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripUpdateDeleteMigrationContractTest {
  private static final String MIGRATION = "20260906000000_trip_update_delete_contract.sql";

  @Test
  void migration은_revision을_결정적으로_backfill하고_단조_양수로_고정한다() throws Exception {
    String sql =
        Files.readString(repositoryRoot().resolve("supabase/migrations").resolve(MIGRATION));

    assertThat(sql)
        .contains("add column revision bigint")
        .contains("update public.trip_plans set revision = 1 where revision is null")
        .contains("alter column revision set default 1")
        .contains("alter column revision set not null")
        .contains("check (revision > 0)");
  }

  @Test
  void migration은_날짜변경_guard를_일정버전과_달력자식_범위검사로_교체한다() throws Exception {
    String sql =
        Files.readString(repositoryRoot().resolve("supabase/migrations").resolve(MIGRATION));

    assertThat(sql)
        .contains("create or replace function public.protect_trip_date_range()")
        .contains("new.timezone is not distinct from old.timezone")
        .contains("from public.trip_schedule_versions")
        .contains("from public.trip_transport_events")
        .contains("from public.trip_accommodations")
        .doesNotContain("delete from public.tour_places")
        .doesNotContain("delete from public.data_import_runs");
  }

  @Test
  void compose는_새_migration을_seed보다_앞선_035로_mount하고_smoke는_revision을_검사한다() throws Exception {
    Path root = repositoryRoot();
    for (String compose :
        java.util.List.of("docker-compose.yml", "compose.yml", "compose.test.yml")) {
      String text = Files.readString(root.resolve(compose));
      int migration = text.indexOf("036_trip_update_delete_contract.sql");
      int seed = text.indexOf("099_seed_fixtures.sql");
      assertThat(migration).as(compose).isGreaterThanOrEqualTo(0).isLessThan(seed);
    }
    assertThat(Files.readString(root.resolve("db/queries/smoke_check.sql")))
        .contains("trip_plans.revision contract is missing")
        .contains("revision <= 0");
  }

  private static Path repositoryRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("supabase/migrations"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new AssertionError("repository root를 찾을 수 없습니다.");
  }
}
