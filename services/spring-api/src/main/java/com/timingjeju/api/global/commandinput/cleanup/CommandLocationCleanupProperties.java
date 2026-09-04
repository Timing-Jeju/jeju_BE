package com.timingjeju.api.global.commandinput.cleanup;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.command-location-cleanup")
public record CommandLocationCleanupProperties(
    @DefaultValue("500") @Min(1) @Max(500) int batchSize, @DefaultValue Schedule schedule) {

  public record Schedule(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("PT5M") Duration fixedDelay,
      @DefaultValue("PT1M") Duration initialDelay,
      @DefaultValue("10") @Min(1) @Max(10) int maxBatches,
      @DefaultValue("3") @Min(1) @Max(3) int retryAttempts,
      @DefaultValue("PT0.25S") Duration initialBackoff,
      @DefaultValue("PT1M") Duration maxDuration) {
    public Schedule {
      Objects.requireNonNull(fixedDelay, "fixedDelay는 필수입니다.");
      Objects.requireNonNull(initialDelay, "initialDelay는 필수입니다.");
      Objects.requireNonNull(initialBackoff, "initialBackoff은 필수입니다.");
      Objects.requireNonNull(maxDuration, "maxDuration은 필수입니다.");
      if (fixedDelay.compareTo(Duration.ofMinutes(5)) < 0
          || fixedDelay.compareTo(Duration.ofDays(1)) > 0
          || initialDelay.isNegative()
          || initialDelay.compareTo(Duration.ofHours(1)) > 0
          || maxBatches < 1
          || maxBatches > 10
          || retryAttempts < 1
          || retryAttempts > 3
          || initialBackoff.compareTo(Duration.ofMillis(1)) < 0
          || initialBackoff.compareTo(Duration.ofSeconds(1)) > 0
          || initialBackoff.multipliedBy(1L << (retryAttempts - 1)).compareTo(Duration.ofSeconds(1))
              > 0
          || maxDuration.compareTo(Duration.ofSeconds(1)) < 0
          || maxDuration.compareTo(Duration.ofMinutes(1)) > 0) {
        throw new IllegalArgumentException("command location cleanup schedule 설정이 올바르지 않습니다.");
      }
    }
  }
}
