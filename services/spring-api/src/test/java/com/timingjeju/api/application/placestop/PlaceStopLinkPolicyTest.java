package com.timingjeju.api.application.placestop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class PlaceStopLinkPolicyTest {

  @Test
  void 반경_500m와_nearest_N_상한을_허용한다() {
    var policy = new PlaceStopLinkPolicy(500, 100, Duration.ofHours(24), Duration.ofHours(6));

    assertThat(policy.radiusMeters()).isEqualTo(500);
    assertThat(policy.maxCandidates()).isEqualTo(100);
  }

  @Test
  void 반경이_1미만이거나_500m를_넘으면_거부한다() {
    assertThatThrownBy(
            () -> new PlaceStopLinkPolicy(0, 3, Duration.ofHours(24), Duration.ofHours(6)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("radiusMeters");
    assertThatThrownBy(
            () -> new PlaceStopLinkPolicy(501, 3, Duration.ofHours(24), Duration.ofHours(6)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("radiusMeters");
  }

  @Test
  void 후보수와_freshness_TTL은_양수여야한다() {
    assertThatThrownBy(
            () -> new PlaceStopLinkPolicy(500, 0, Duration.ofHours(24), Duration.ofHours(6)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxCandidates");
    assertThatThrownBy(() -> new PlaceStopLinkPolicy(500, 3, Duration.ZERO, Duration.ofHours(6)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("linkTtl");
    assertThatThrownBy(() -> new PlaceStopLinkPolicy(500, 3, Duration.ofHours(24), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stopFreshnessTtl");
  }
}
