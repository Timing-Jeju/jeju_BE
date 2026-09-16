package com.timingjeju.api.domain.accountdeletion.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class AccountDeletionConfigurationTest {
  @Test
  void recentAuth와_statusToken_TTL은_양수인_승인범위만_허용한다() {
    assertThatThrownBy(
            () ->
                AccountDeletionConfiguration.validateDurations(Duration.ZERO, Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountDeletionConfiguration.validateDurations(
                    Duration.ofMinutes(15), Duration.ofDays(8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountDeletionConfiguration.validateDurations(
                    Duration.ofHours(25), Duration.ofHours(24)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
