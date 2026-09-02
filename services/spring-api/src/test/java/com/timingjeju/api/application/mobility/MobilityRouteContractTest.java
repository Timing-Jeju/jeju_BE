package com.timingjeju.api.application.mobility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class MobilityRouteContractTest {
  @Test
  void 정규화_mode_matrix는_대중교통_차량_택시_보행을_정확히_지원한다() {
    assertThat(EnumSet.allOf(MobilityMode.class))
        .containsExactlyInAnyOrder(
            MobilityMode.PUBLIC_TRANSIT,
            MobilityMode.RENTAL_CAR,
            MobilityMode.TAXI,
            MobilityMode.WALK);
  }

  @Test
  void duration은_구성요소_합계이며_fare는_nullable이다() {
    MobilityRouteMeasurement measurement =
        new MobilityRouteMeasurement(
            MobilityMode.PUBLIC_TRANSIT,
            9_500,
            new MobilityDurationComponents(7, 8, 22, 4, 6),
            null,
            Duration.ofHours(1));

    assertThat(measurement.durationMinutes()).isEqualTo(47);
    assertThat(measurement.fareKrw()).isNull();
  }

  @Test
  void 좌표와_route_수치의_비유한값_음수_상한초과를_거부한다() {
    assertThatThrownBy(() -> new MobilityPoint(Double.NaN, 126.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MobilityPoint(33.5, Double.POSITIVE_INFINITY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MobilityPoint(91, 126.5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MobilityDurationComponents(0, -1, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MobilityRouteMeasurement(
                    MobilityMode.WALK,
                    -1,
                    new MobilityDurationComponents(0, 0, 1, 0, 0),
                    null,
                    Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void mode별_TTL_상한과_경계값을_fail_closed로_검증한다() {
    assertThat(
            new MobilityRouteMeasurement(
                    MobilityMode.WALK,
                    1,
                    new MobilityDurationComponents(0, 0, 1, 0, 0),
                    null,
                    Duration.ofHours(23).plusMinutes(50))
                .validFor())
        .isEqualTo(Duration.ofHours(23).plusMinutes(50));

    assertThatThrownBy(
            () ->
                new MobilityRouteMeasurement(
                    MobilityMode.WALK,
                    1,
                    new MobilityDurationComponents(0, 0, 1, 0, 0),
                    null,
                    Duration.ofHours(24)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MobilityRouteMeasurement(
                    MobilityMode.RENTAL_CAR,
                    1,
                    new MobilityDurationComponents(0, 0, 1, 0, 0),
                    null,
                    Duration.ofMinutes(5).plusNanos(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MobilityRouteMeasurement(
                    MobilityMode.TAXI,
                    1,
                    new MobilityDurationComponents(0, 0, 1, 0, 0),
                    null,
                    Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cache_hash는_source와_좌표_시간_mode를_포함하되_원문좌표를_노출하지_않는다() {
    MobilityRouteRequest request =
        new MobilityRouteRequest(
            new MobilityPoint(33.5067, 126.4930),
            new MobilityPoint(33.5104, 126.4913),
            MobilityMode.WALK,
            Instant.parse("2026-09-02T09:00:00Z"));

    String hash = MobilityRouteRequestHasher.hash("official.walking", request);
    String differentSource = MobilityRouteRequestHasher.hash("another.walking", request);
    String differentMode =
        MobilityRouteRequestHasher.hash(
            "official.walking",
            new MobilityRouteRequest(
                request.origin(),
                request.destination(),
                MobilityMode.RENTAL_CAR,
                request.departureAt()));

    assertThat(hash).matches("[0-9a-f]{64}");
    assertThat(hash).doesNotContain("33.5067", "126.493");
    assertThat(differentSource).isNotEqualTo(hash);
    assertThat(differentMode).isNotEqualTo(hash);
  }

  @Test
  void 보행_추정_reason은_WALK_mode에서만_허용한다() {
    String requestHash = "a".repeat(64);
    Instant observedAt = Instant.parse("2026-09-02T00:00:00Z");
    MobilityDurationComponents duration = new MobilityDurationComponents(0, 0, 10, 0, 0);

    for (MobilityMode mode :
        EnumSet.of(MobilityMode.PUBLIC_TRANSIT, MobilityMode.RENTAL_CAR, MobilityMode.TAXI)) {
      assertThatThrownBy(
              () ->
                  new MobilityRouteFact(
                      requestHash,
                      "conservative-walk-policy",
                      mode,
                      1_000,
                      duration,
                      null,
                      observedAt,
                      observedAt.plus(Duration.ofMinutes(1)),
                      false,
                      true,
                      MobilityRouteReason.ESTIMATED_WALK_TIME))
          .isInstanceOf(IllegalArgumentException.class);
    }

    assertThat(
            new MobilityRouteFact(
                    requestHash,
                    "conservative-walk-policy",
                    MobilityMode.WALK,
                    1_000,
                    duration,
                    null,
                    observedAt,
                    observedAt.plus(Duration.ofMinutes(1)),
                    false,
                    true,
                    MobilityRouteReason.ESTIMATED_WALK_TIME)
                .mode())
        .isEqualTo(MobilityMode.WALK);
  }
}
