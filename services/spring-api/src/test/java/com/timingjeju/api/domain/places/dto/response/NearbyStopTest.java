package com.timingjeju.api.domain.places.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.timingjeju.api.domain.places.model.PlaceDetailNearbyStopRow;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class NearbyStopTest {

  private static final UUID STOP_ID = UUID.fromString("66000000-0000-0000-0000-000000000001");
  private static final Instant OBSERVED_AT = Instant.parse("2026-08-21T06:00:00Z");

  @Test
  void effective_expiry는_link_observedAt보다_이전과_이후를_모두_허용한다() {
    Instant olderStopExpiry = OBSERVED_AT.minusSeconds(1);
    Instant laterLinkExpiry = OBSERVED_AT.plusSeconds(1);

    assertThat(row("postgis:tago", "spatial_radius", olderStopExpiry).expiresAt())
        .isEqualTo(olderStopExpiry);
    assertThat(dto("postgis:tago", "spatial_radius", olderStopExpiry).expiresAt())
        .isEqualTo(olderStopExpiry);
    assertThat(row("postgis:tago", "spatial_radius", laterLinkExpiry).expiresAt())
        .isEqualTo(laterLinkExpiry);
    assertThat(dto("postgis:tago", "spatial_radius", laterLinkExpiry).expiresAt())
        .isEqualTo(laterLinkExpiry);
  }

  @Test
  void provider는_공백이_아니며_Unicode_code_point_128개까지다() {
    String unicode128 = "🍊".repeat(128);
    String unicode129 = unicode128 + "🍊";

    assertThat(row(unicode128, "spatial_radius", OBSERVED_AT).provider()).isEqualTo(unicode128);
    assertThat(dto(unicode128, "spatial_radius", OBSERVED_AT).provider()).isEqualTo(unicode128);
    assertThatThrownBy(() -> row(" ", "spatial_radius", OBSERVED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> dto(" ", "spatial_radius", OBSERVED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> row(unicode129, "spatial_radius", OBSERVED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> dto(unicode129, "spatial_radius", OBSERVED_AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void linkMethod는_계약_enum만_허용한다() {
    for (String method : new String[] {"spatial_radius", "fixture", "manual", "api_nearby"}) {
      assertThat(row("postgis:tago", method, OBSERVED_AT).linkMethod()).isEqualTo(method);
      assertThat(dto("postgis:tago", method, OBSERVED_AT).linkMethod()).isEqualTo(method);
    }
    assertThatThrownBy(() -> row("postgis:tago", "legacy_raw", OBSERVED_AT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> dto("postgis:tago", "legacy_raw", OBSERVED_AT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PlaceDetailNearbyStopRow row(
      String provider, String linkMethod, Instant expiresAt) {
    return new PlaceDetailNearbyStopRow(
        STOP_ID, "성산일출봉입구", 100, 2, linkMethod, provider, OBSERVED_AT, expiresAt, true);
  }

  private static NearbyStop dto(String provider, String linkMethod, Instant expiresAt) {
    return new NearbyStop(
        STOP_ID, "성산일출봉입구", 100, 2, linkMethod, provider, OBSERVED_AT, expiresAt, true);
  }
}
