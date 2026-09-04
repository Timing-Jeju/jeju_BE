package com.timingjeju.api.domain.accommodation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AccommodationMigrationContractTest {
  private static final String MIGRATION = "20260907000002_trip_accommodation_contract.sql";

  @Test
  void migration은_legacy를_추측보정하지않고_XOR_time_idempotency를_강제한다() throws Exception {
    String sql = Files.readString(root().resolve("supabase/migrations").resolve(MIGRATION));

    assertThat(sql)
        .contains("legacy accommodation contract conflict")
        .contains("num_nonnulls(place_id, custom_name) = 1")
        .contains("alter column check_in_time set not null")
        .contains("alter column check_out_time set not null")
        .contains("create table public.accommodation_idempotency")
        .contains("create index ix_accommodation_idempotency_trip")
        .contains("expires_at = created_at + interval '24 hours'")
        .contains(
            "response_etag ~ '^\"trip-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-r[1-9][0-9]*\"$'")
        .contains("completed accommodation idempotency snapshot is immutable")
        .doesNotContain("delete from public.trip_accommodations")
        .doesNotContain("Flyway");
  }

  @Test
  void 숙소_구현은_공용_trip_aggregate_ETag만_사용한다() throws Exception {
    Path spring = root().resolve("services/spring-api/src/main/java");
    String sources =
        String.join(
            "\n",
            Files.readString(
                spring.resolve(
                    "com/timingjeju/api/domain/accommodation/controller/AccommodationController.java")),
            Files.readString(
                spring.resolve(
                    "com/timingjeju/api/application/accommodation/AccommodationCreateRecord.java")),
            Files.readString(
                spring.resolve(
                    "com/timingjeju/api/application/accommodation/AccommodationPatchRecord.java")),
            Files.readString(
                spring.resolve(
                    "com/timingjeju/api/application/accommodation/AccommodationDeleteRecord.java")),
            Files.readString(
                spring.resolve(
                    "com/timingjeju/api/application/accommodation/service/AccommodationService.java")));

    assertThat(sources)
        .contains("TripEntityTag.parse(ifMatch)")
        .contains("expected.tripId().equals(canonicalTripId)")
        .doesNotContain("TripEntityTag.expectedRevision");
  }

  @Test
  void compose와_Docker_upgrade는_036을_seed전에_정확히_적용한다() throws Exception {
    Path root = root();
    String target = "039_trip_accommodation_contract.sql";
    for (String compose :
        java.util.List.of("compose.yml", "compose.test.yml", "docker-compose.yml")) {
      String text = Files.readString(root.resolve(compose));
      assertThat(text.indexOf(target)).as(compose).isGreaterThanOrEqualTo(0);
      assertThat(text.indexOf(target))
          .as(compose)
          .isLessThan(text.indexOf("099_seed_fixtures.sql"));
    }
    assertThat(Files.readString(root.resolve("scripts/docker-smoke-test.sh")))
        .contains("/docker-entrypoint-initdb.d/039_trip_accommodation_contract.sql");
  }

  private static Path root() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve("supabase/migrations"))) return current;
      current = current.getParent();
    }
    throw new AssertionError("repository root를 찾을 수 없습니다.");
  }
}
