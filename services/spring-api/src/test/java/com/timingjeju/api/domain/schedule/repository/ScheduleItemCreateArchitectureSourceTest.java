package com.timingjeju.api.domain.schedule.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class ScheduleItemCreateArchitectureSourceTest {
  @Test
  void 일정_편집_네_endpoint는_printable_ASCII_멱등성_namespace를_공통으로_사용한다() throws Exception {
    String controller =
        Files.readString(
            repositoryRoot()
                .resolve("services/spring-api/src/main/java")
                .resolve(
                    "com/timingjeju/api/domain/schedule/controller/ScheduleMutationController.java"));

    assertThat(controller)
        .contains("@PatchMapping(\"/schedule-items/{itemId}\")")
        .contains("@DeleteMapping(\"/schedule-items/{itemId}\")")
        .contains("@PutMapping(\"/schedule-order\")")
        .contains("@PostMapping(\"/schedule-items/{itemId}/move\")")
        .doesNotContain("IdempotencyRequest.create(");
    assertThat(occurrences(controller, "ScheduleIdempotencyKey.createRequest(")).isEqualTo(2);
    assertThat(occurrences(controller, "return executeMutation(")).isEqualTo(4);
  }

  @Test
  void 일정_편집은_monotonic_coordinator와_새_item_namespace를_사용한다() throws Exception {
    String source = scheduleStoreSource();

    assertThat(source)
        .contains(".executeMonotonic(")
        .contains("changedIds.stream().map(copiedIds::get)")
        .contains("items -> delete(items, record), List.of())")
        .doesNotContain("lockOwnedTrip(")
        .doesNotContain("revision=revision+1");
  }

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

  private static String scheduleStoreSource() throws Exception {
    return Files.readString(
        repositoryRoot()
            .resolve("services/spring-api/src/main/java")
            .resolve("com/timingjeju/api/domain/schedule/adapter/JdbcScheduleMutationStore.java"));
  }

  private static int occurrences(String value, String target) {
    int count = 0;
    int offset = 0;
    while ((offset = value.indexOf(target, offset)) >= 0) {
      count++;
      offset += target.length();
    }
    return count;
  }
}
