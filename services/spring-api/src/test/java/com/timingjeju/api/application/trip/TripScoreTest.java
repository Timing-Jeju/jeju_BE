package com.timingjeju.api.application.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class TripScoreTest {
  private static final UUID ACTIVE = UUID.fromString("44000000-0000-0000-0000-000000000051");
  private static final UUID RUN = UUID.fromString("44000000-0000-0000-0000-000000000052");
  private static final Instant OBSERVED = Instant.parse("2026-08-25T00:00:00Z");
  private static final Instant CALCULATED = Instant.parse("2026-08-25T00:01:00Z");
  private static final Instant EXPIRES = Instant.parse("2026-08-25T00:02:00Z");

  @Test
  void active_schedule의_latest_successful_run이_완전하고_시간순이면_score와_provenance가_같이_생긴다() {
    TripScore score =
        TripScore.resolve(ACTIVE, 81, RUN, ACTIVE, CALCULATED, OBSERVED, EXPIRES, EXPIRES);

    assertThat(score.totalScore()).isEqualTo(81);
    assertThat(score.provenance().runId()).isEqualTo(RUN);
    assertThat(score.provenance().scheduleVersionId()).isEqualTo(ACTIVE);
    assertThat(score.provenance().stale()).isTrue();
  }

  @Test
  void run이_없거나_active_schedule이_다르거나_freshness가_불완전하면_둘다_null이다() {
    assertNullScore(TripScore.resolve(ACTIVE, null, null, null, null, null, null, CALCULATED));
    assertNullScore(
        TripScore.resolve(
            ACTIVE, 81, RUN, UUID.randomUUID(), CALCULATED, OBSERVED, EXPIRES, CALCULATED));
    assertNullScore(
        TripScore.resolve(ACTIVE, 81, RUN, ACTIVE, CALCULATED, OBSERVED, null, CALCULATED));
    assertNullScore(
        TripScore.resolve(ACTIVE, 81, RUN, ACTIVE, CALCULATED, EXPIRES, OBSERVED, CALCULATED));
  }

  private static void assertNullScore(TripScore score) {
    assertThat(score.totalScore()).isNull();
    assertThat(score.provenance()).isNull();
  }
}
