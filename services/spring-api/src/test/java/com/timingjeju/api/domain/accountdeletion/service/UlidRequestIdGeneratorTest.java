package com.timingjeju.api.domain.accountdeletion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.domain.accountdeletion.adapter.UlidAccountDeletionRequestIdProvider;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class UlidRequestIdGeneratorTest {
  @Test
  void request_id는_26자_canonical_Crockford_ULID다() {
    var generator =
        new UlidAccountDeletionRequestIdProvider(
            Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC),
            new SecureRandom(new byte[] {1, 2, 3}));

    assertThat(generator.generate()).matches("^[0-9A-HJKMNP-TV-Z]{26}$");
  }
}
