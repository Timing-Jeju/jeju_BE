package com.timingjeju.api.domain.trip.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.trip.TripScore;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class JdbcTripScoreJsonTest {
  private static final UUID ACTIVE = UUID.fromString("44000000-0000-0000-0000-000000000951");
  private static final UUID RUN = UUID.fromString("44000000-0000-0000-0000-000000000952");
  private static final Instant FACTS = Instant.parse("2026-08-25T00:00:00Z");
  private static final Instant CALCULATED = Instant.parse("2026-08-25T00:01:00Z");
  private static final Instant EXPIRES = Instant.parse("2026-08-25T00:02:00Z");

  @Test
  void score는_JSON_number_정수만_허용하고_string이나_fraction은_거부한다() {
    assertThat(resolve("number", "81", false, null, null).totalScore()).isEqualTo(81);
    assertEmpty(resolve("string", "81", false, null, null));
    assertThat(resolve("number", "81.0", false, null, null).totalScore()).isEqualTo(81);
    assertEmpty(resolve("number", "81.5", false, null, null));
    assertEmpty(resolve("number", "101", false, null, null));
  }

  @Test
  void observedAt이_진짜_absent일_때만_facts_snapshot으로_fallback한다() {
    TripScore absent = resolve("number", "81", false, null, null);
    TripScore malformed = resolve("number", "81", true, "string", "not-an-instant");
    TripScore wrongType = resolve("number", "81", true, "number", "123");

    assertThat(absent.provenance().observedAt()).isEqualTo(FACTS);
    assertEmpty(malformed);
    assertEmpty(wrongType);
  }

  @Test
  void observedAt의_유효한_offset_string은_동일_instant로_보존한다() {
    TripScore score = resolve("number", "81", true, "string", "2026-08-25T09:00:00+09:00");

    assertThat(score.provenance().observedAt()).isEqualTo(FACTS);
  }

  private static TripScore resolve(
      String scoreType,
      String scoreValue,
      boolean observedPresent,
      String observedType,
      String observedValue) {
    return JdbcTripStore.resolveScore(
        ACTIVE,
        RUN,
        ACTIVE,
        CALCULATED,
        EXPIRES,
        scoreType,
        scoreValue,
        observedPresent,
        observedType,
        observedValue,
        "string",
        EXPIRES.toString(),
        FACTS);
  }

  private static void assertEmpty(TripScore score) {
    assertThat(score.totalScore()).isNull();
    assertThat(score.provenance()).isNull();
  }
}
