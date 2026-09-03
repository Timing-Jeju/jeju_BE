package com.timingjeju.api.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ScheduleItemCreateMigrationContractTest {
  private static final String MIGRATION = "20260907000000_schedule_item_create_contract.sql";
  private static final String TARGET =
      "/docker-entrypoint-initdb.d/037_schedule_item_create_contract.sql";

  @Test
  void migration은_숙소와_교통이벤트를_동일여행_복합FK로_제한한다() throws Exception {
    String sql =
        Files.readString(repositoryRoot().resolve("supabase/migrations").resolve(MIGRATION));

    assertThat(sql)
        .contains("add column accommodation_id uuid")
        .contains("add column transport_event_id uuid")
        .contains("foreign key (accommodation_id, trip_plan_id)")
        .contains("references public.trip_accommodations (id, trip_plan_id)")
        .contains("foreign key (transport_event_id, trip_plan_id)")
        .contains("references public.trip_transport_events (id, trip_plan_id)")
        .doesNotContain("raw_payload")
        .doesNotContain("geometry");
  }

  @Test
  void compose와_legacy_upgrade는_새_migration을_seed보다_먼저_적용한다() throws Exception {
    Path root = repositoryRoot();
    String source = "./supabase/migrations/" + MIGRATION;
    for (String compose : List.of("docker-compose.yml", "compose.yml", "compose.test.yml")) {
      String contents = Files.readString(root.resolve(compose));
      assertThat(contents).as(compose).contains(source).contains(TARGET);
      assertThat(contents.indexOf(TARGET))
          .as(compose)
          .isLessThan(contents.indexOf("/docker-entrypoint-initdb.d/099_seed_fixtures.sql"));
    }

    String smoke = Files.readString(root.resolve("scripts/docker-smoke-test.sh"));
    assertThat(smoke.split(java.util.regex.Pattern.quote(TARGET), -1)).hasSize(3);
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
