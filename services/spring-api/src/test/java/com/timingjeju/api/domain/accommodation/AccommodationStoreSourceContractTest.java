package com.timingjeju.api.domain.accommodation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class AccommodationStoreSourceContractTest {
  @Test
  void delete는_대상_숙소_존재를_active_schedule보다_먼저_확인한다() throws Exception {
    String source =
        Files.readString(
            root()
                .resolve(
                    "services/spring-api/src/main/java/com/timingjeju/api/domain/accommodation/adapter/JdbcAccommodationStore.java"));
    int method = source.indexOf("public void delete(AccommodationDeleteRecord record)");
    int missing = source.indexOf("if (targetIndex < 0)", method);
    int active = source.indexOf("if (state.activeScheduleVersionId() != null)", method);

    assertThat(method).isGreaterThanOrEqualTo(0);
    assertThat(missing).isGreaterThan(method);
    assertThat(active).isGreaterThan(missing);
  }

  @Test
  void accommodation_store는_canonical_trip_coordinator만_aggregate_lock과_CAS를_소유하게_한다()
      throws Exception {
    String source =
        Files.readString(
            root()
                .resolve(
                    "services/spring-api/src/main/java/com/timingjeju/api/domain/accommodation/adapter/JdbcAccommodationStore.java"));

    assertThat(source)
        .contains("TripAggregateMutationCoordinator")
        .contains("TripAggregateMutationPlan.noChange(")
        .contains("TripAggregateMutationPlan.maintain(")
        .contains("TripAggregateMutationPlan.invalidate(")
        .contains("catch (TripException failure)")
        .contains("case \"TRIP_NOT_FOUND\" -> AccommodationException.of(\"TRIP_NOT_FOUND\")")
        .contains("\"TRIP_TERMINAL_STATE_CONFLICT\"")
        .contains("AccommodationException.of(\"ACCOMMODATION_CONCURRENT_CONFLICT\")")
        .contains("select pg_advisory_xact_lock")
        .contains("from public.accommodation_idempotency")
        .contains("for update")
        .doesNotContain("lockOwned(")
        .doesNotContain("advanceRoot(")
        .doesNotContain("MutationRoot")
        .doesNotContain("revision = revision + 1")
        .doesNotContain("set status = 'superseded'");

    int replay = source.indexOf("return AccommodationCreateStoreResult.replayed");
    int coordinator = source.indexOf("coordinator.execute(", replay);
    assertThat(replay).isGreaterThanOrEqualTo(0).isLessThan(coordinator);
  }

  @Test
  void canonical_trip_coordinator는_50fix와_monotonic_extension의_exact_provenance를_유지한다()
      throws Exception {
    Path java = root().resolve("services/spring-api/src/main/java");
    Map<String, String> expected =
        Map.ofEntries(
            Map.entry(
                "com/timingjeju/api/application/trip/TripAggregateMutationCommit.java",
                "581af6003b851eb907e2b427fcc54e6da489e7b7006fa94558f9f8a1e0dfb8b4"),
            Map.entry(
                "com/timingjeju/api/application/trip/TripAggregateMutationCoordinator.java",
                "38c6dd94125c6e86a45b376f2fe7fa756b476f507d943b5b6fbf4ecb23be893a"),
            Map.entry(
                "com/timingjeju/api/application/trip/TripAggregateTimestampedMutationOperation.java",
                "c04a62f649696fb80a0a061f9d193febdab59902ac4847f91721bed6f70bf663"),
            Map.entry(
                "com/timingjeju/api/application/trip/TripAggregateMutationEffect.java",
                "7b75268d4b14d9e4d20f8a63039c5d2aa95b7f0012693d140415cb551b30d477"),
            Map.entry(
                "com/timingjeju/api/application/trip/TripAggregateMutationOperation.java",
                "88fe5894850ca0f677adaec84b05f8dfff01038b787a5e8452e69d05e382fea6"),
            Map.entry(
                "com/timingjeju/api/application/trip/TripAggregateMutationPlan.java",
                "1d5e0280f0d16d089ec7ad999475d5671b4527224d61985940b9087a5ee48216"),
            Map.entry(
                "com/timingjeju/api/application/trip/TripAggregateMutationState.java",
                "8132f5ed0c62cbcd8a215b9e9ffc809a89252301ec4ee441a2fa0f10fd6182c7"),
            Map.entry(
                "com/timingjeju/api/application/trip/TripRootPatch.java",
                "f7b15e6a2d4d312786822022da2be6ca9b419c3dc713d3c9d7588ba18d33527a"),
            Map.entry(
                "com/timingjeju/api/application/trip/TripScheduleEffect.java",
                "e310eb5dde8af5d622cc2bfc7e2bb718f398e68cef3d2ff4af62c083d6c2203a"),
            Map.entry(
                "com/timingjeju/api/domain/trip/adapter/JdbcTripAggregateMutationCoordinator.java",
                "dfb7800d4b409b9482c4eafa568a46ea5309d039a1f35f9c3e81beb5e7dddd92"));

    for (Map.Entry<String, String> entry : expected.entrySet()) {
      assertThat(sha256(java.resolve(entry.getKey())))
          .as("#50fix canonical provenance + #48 monotonic extension: %s", entry.getKey())
          .isEqualTo(entry.getValue());
    }
  }

  private static String sha256(Path path) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  }

  private static Path root() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("services/spring-api"))) return current;
      current = current.getParent();
    }
    throw new AssertionError("repository root를 찾을 수 없습니다.");
  }
}
