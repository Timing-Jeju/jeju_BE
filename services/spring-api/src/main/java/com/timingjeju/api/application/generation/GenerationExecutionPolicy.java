package com.timingjeju.api.application.generation;

import com.timingjeju.api.application.asyncrun.RunExecutionPolicy;
import java.time.Duration;

public final class GenerationExecutionPolicy {
  private GenerationExecutionPolicy() {}

  public static RunExecutionPolicy forRequestTimeout(Duration requestTimeout) {
    if (requestTimeout == null || requestTimeout.compareTo(Duration.ofSeconds(165)) < 0) {
      throw new IllegalArgumentException("생성 요청 제한시간은 165초 이상이어야 합니다.");
    }
    return new RunExecutionPolicy(
        requestTimeout.plusSeconds(15),
        Duration.ofSeconds(10),
        1,
        3,
        Duration.ofSeconds(1),
        Duration.ofSeconds(60),
        requestTimeout.multipliedBy(4).plusSeconds(15));
  }
}
