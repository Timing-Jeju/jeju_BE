package com.timingjeju.api.global.retention;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.snapshot-retention.schedule")
public record SnapshotRetentionScheduleProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("PT24H") Duration fixedDelay,
    @DefaultValue("PT1M") Duration initialDelay,
    @DefaultValue("10") @Min(1) @Max(10) int maxBatches,
    @DefaultValue("3") @Min(1) @Max(3) int retryAttempts,
    @DefaultValue("PT0.25S") Duration initialBackoff) {

  public SnapshotRetentionScheduleProperties {
    if (maxBatches < 1
        || maxBatches > 10
        || retryAttempts < 1
        || retryAttempts > 3
        || fixedDelay.compareTo(Duration.ofMinutes(1)) < 0
        || fixedDelay.compareTo(Duration.ofDays(7)) > 0
        || initialDelay.isNegative()
        || initialDelay.compareTo(Duration.ofHours(24)) > 0
        || initialBackoff.compareTo(Duration.ofMillis(1)) < 0
        || initialBackoff.compareTo(Duration.ofSeconds(1)) > 0
        || initialBackoff.multipliedBy(1L << (retryAttempts - 1)).compareTo(Duration.ofSeconds(1))
            > 0) {
      throw new IllegalArgumentException("snapshot retention schedule 설정이 올바르지 않습니다.");
    }
  }
}
