package com.timingjeju.api.global.retention;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.saved-place-retention")
public record SavedPlaceRetentionProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("PT24H") Duration fixedDelay,
    @DefaultValue("PT1M") Duration initialDelay,
    @DefaultValue("10") @Min(1) @Max(10) int maxBatches) {

  public SavedPlaceRetentionProperties {
    if (fixedDelay.compareTo(Duration.ofHours(1)) < 0
        || fixedDelay.compareTo(Duration.ofDays(7)) > 0
        || initialDelay.isNegative()
        || initialDelay.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalArgumentException("saved-place retention 설정이 올바르지 않습니다.");
    }
  }
}
