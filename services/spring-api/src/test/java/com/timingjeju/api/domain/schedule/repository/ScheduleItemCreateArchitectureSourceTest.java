package com.timingjeju.api.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ScheduleItemCreateArchitectureSourceTest {
  @Test
  void 일정_item_store는_공용_trip_aggregate_coordinator_밖에서_lock_CAS_revision을_복제하지_않는다()
      throws Exception {
    String source =
        Files.readString(
            repositoryRoot()
                .resolve("services/spring-api/src/main/java")
                .resolve(
                    "com/timingjeju/api/domain/schedule/adapter/JdbcScheduleMutationStore.java"));

    assertThat(source)
        .contains("TripAggregateMutationCoordinator")
        .contains(".executeMonotonic(")
        .doesNotContain("for update")
        .doesNotContain("revision=revision+1")
        .doesNotContain("lockOwnedTrip(")
        .doesNotContain("private record Root(");
  }

  @Test
  void 공용_coordinator는_lock_beforeRoot_CAS_effect_순서를_단일_경계에_둔다() throws Exception {
    String source =
        Files.readString(
            repositoryRoot()
                .resolve("services/spring-api/src/main/java")
                .resolve(
                    "com/timingjeju/api/domain/trip/adapter/JdbcTripAggregateMutationCoordinator.java"));

    int lock = source.indexOf("for update");
    int beforeRoot = source.indexOf("plan.beforeRootEffect().apply()");
    int revisionCas = source.indexOf("revision = revision + 1");
    int effect = source.indexOf("plan.effect().apply()");
    assertThat(lock).isGreaterThanOrEqualTo(0).isLessThan(beforeRoot);
    assertThat(beforeRoot).isLessThan(revisionCas);
    assertThat(revisionCas).isLessThan(effect);
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
