package com.timingjeju.api.application.accommodation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AccommodationProjectionInvariantTest {
  private static final UUID ACCOMMODATION = UUID.fromString("68000000-0000-0000-0000-000000000881");
  private static final UUID PLACE = UUID.fromString("68000000-0000-0000-0000-000000000882");

  @Test
  void place_name은_응답_schema의_100자_경계를_허용한다() {
    assertThat(project("가".repeat(100)).name()).hasSize(100);
  }

  @Test
  void place_name의_101자_blank_control_비정규화는_안정적인_비가용_오류로_차단한다() {
    for (String invalid : List.of("가".repeat(101), "   ", " 숙소 ", "숙소\u0000명", "e\u0301")) {
      AccommodationException failure =
          catchThrowableOfType(AccommodationException.class, () -> project(invalid));

      assertThat(failure).isNotNull();
      assertThat(failure.code()).isEqualTo("ACCOMMODATION_DATA_UNAVAILABLE");
      assertThat(failure.getMessage()).doesNotContain(invalid);
    }
  }

  private static Accommodation project(String name) {
    return new Accommodation(
        ACCOMMODATION,
        PLACE,
        null,
        name,
        LocalDate.parse("2026-09-01"),
        LocalDate.parse("2026-09-02"),
        LocalTime.parse("15:00"),
        LocalTime.parse("11:00"),
        1,
        Instant.parse("2026-09-01T06:00:00Z"),
        Instant.parse("2026-09-01T06:00:00Z"));
  }
}
