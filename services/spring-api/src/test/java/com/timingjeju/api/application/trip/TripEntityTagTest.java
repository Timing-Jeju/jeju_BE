package com.timingjeju.api.application.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripEntityTagTest {
  private static final UUID TRIP = UUID.fromString("45000000-0000-0000-0000-000000000001");

  @Test
  void strong_ETag는_trip_id와_영속_revision을_정확히_인코딩한다() {
    String etag = TripEntityTag.strong(TRIP, 7);

    assertThat(etag).isEqualTo("\"trip-45000000-0000-0000-0000-000000000001-r7\"");
    assertThat(TripEntityTag.parse(etag)).isEqualTo(new TripExpectedRevision(TRIP, 7));
  }

  @Test
  void If_Match_누락과_weak_wildcard_비정규_tag를_서로_다른_code로_거부한다() {
    assertThatThrownBy(() -> TripEntityTag.parse(null))
        .isInstanceOf(TripException.class)
        .extracting(failure -> ((TripException) failure).code())
        .isEqualTo("IF_MATCH_REQUIRED");

    for (String malformed :
        java.util.List.of(
            "", "W/\"trip-45000000-0000-0000-0000-000000000001-r7\"", "*", "\"trip-r0\"")) {
      assertThatThrownBy(() -> TripEntityTag.parse(malformed))
          .isInstanceOf(TripException.class)
          .extracting(failure -> ((TripException) failure).code())
          .isEqualTo("INVALID_IF_MATCH");
    }
  }
}
