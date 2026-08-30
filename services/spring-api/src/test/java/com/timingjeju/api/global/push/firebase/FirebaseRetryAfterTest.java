package com.timingjeju.api.global.push.firebase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class FirebaseRetryAfterTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-26T04:00:00Z"), ZoneOffset.UTC);

  @Test
  void delay_seconds와_HTTP_date를_String_List_header_shape에서_해석한다() {
    assertThat(FirebaseAdminMessagingGateway.retryAfter(Map.of("Retry-After", "17"), CLOCK))
        .isEqualTo(Duration.ofSeconds(17));
    assertThat(
            FirebaseAdminMessagingGateway.retryAfter(
                Map.of("retry-after", List.of("Wed, 26 Aug 2026 04:00:30 GMT")), CLOCK))
        .isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void malformed_past_zero_multiple_value는_retry_hint없이_fail_closed한다() {
    for (Object value :
        List.of(
            "malformed", "0", "Wed, 26 Aug 2026 03:59:59 GMT", List.of("17", "18"), List.of())) {
      assertThat(FirebaseAdminMessagingGateway.retryAfter(Map.of("Retry-After", value), CLOCK))
          .as(value.toString())
          .isNull();
    }
  }
}
