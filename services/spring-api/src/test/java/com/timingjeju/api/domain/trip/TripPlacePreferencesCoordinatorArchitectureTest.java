package com.timingjeju.api.domain.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("architecture")
class TripPlacePreferencesCoordinatorArchitectureTest {
  private static final Path JAVA = Path.of("src/main/java/com/timingjeju/api");
  private static final Map<String, String> COORDINATOR_SHA256 =
      Map.ofEntries(
          Map.entry(
              "application/trip/TripAggregateMutationCommit.java",
              "581af6003b851eb907e2b427fcc54e6da489e7b7006fa94558f9f8a1e0dfb8b4"),
          Map.entry(
              "application/trip/TripAggregateMutationCoordinator.java",
              "38c6dd94125c6e86a45b376f2fe7fa756b476f507d943b5b6fbf4ecb23be893a"),
          Map.entry(
              "application/trip/TripAggregateTimestampedMutationOperation.java",
              "c04a62f649696fb80a0a061f9d193febdab59902ac4847f91721bed6f70bf663"),
          Map.entry(
              "application/trip/TripAggregateMutationEffect.java",
              "7b75268d4b14d9e4d20f8a63039c5d2aa95b7f0012693d140415cb551b30d477"),
          Map.entry(
              "application/trip/TripAggregateMutationOperation.java",
              "88fe5894850ca0f677adaec84b05f8dfff01038b787a5e8452e69d05e382fea6"),
          Map.entry(
              "application/trip/TripAggregateMutationPlan.java",
              "1d5e0280f0d16d089ec7ad999475d5671b4527224d61985940b9087a5ee48216"),
          Map.entry(
              "application/trip/TripAggregateMutationState.java",
              "8132f5ed0c62cbcd8a215b9e9ffc809a89252301ec4ee441a2fa0f10fd6182c7"),
          Map.entry(
              "application/trip/TripRootPatch.java",
              "f7b15e6a2d4d312786822022da2be6ca9b419c3dc713d3c9d7588ba18d33527a"),
          Map.entry(
              "application/trip/TripScheduleEffect.java",
              "e310eb5dde8af5d622cc2bfc7e2bb718f398e68cef3d2ff4af62c083d6c2203a"),
          Map.entry(
              "domain/trip/adapter/JdbcTripAggregateMutationCoordinator.java",
              "dfb7800d4b409b9482c4eafa568a46ea5309d039a1f35f9c3e81beb5e7dddd92"));

  @Test
  void coordinator는_기존_contract와_monotonic_timestamp_extension을_byte_exact로_고정한다()
      throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (var entry : COORDINATOR_SHA256.entrySet()) {
      String actual =
          HexFormat.of().formatHex(digest.digest(Files.readAllBytes(JAVA.resolve(entry.getKey()))));
      assertThat(actual).as(entry.getKey()).isEqualTo(entry.getValue());
    }
  }

  @Test
  void 장소선호_store는_coordinator를_소비하고_root_lock과_revision_CAS를_복제하지_않는다() throws Exception {
    String source =
        Files.readString(JAVA.resolve("domain/trip/adapter/JdbcTripPlacePreferencesStore.java"));

    assertThat(source)
        .contains("TripAggregateMutationCoordinator", "mutations.executeMonotonic(")
        .doesNotContainIgnoringCase(" for update");
    assertThat(source).doesNotMatch("(?s).*revision\\s*=\\s*revision\\s*\\+\\s*1.*");
  }
}
