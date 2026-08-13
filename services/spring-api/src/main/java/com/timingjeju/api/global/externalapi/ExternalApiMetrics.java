package com.timingjeju.api.global.externalapi;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;

final class ExternalApiMetrics {

  static final String METRIC_NAME = "timingjeju.external.api.requests";

  private final MeterRegistry registry;

  ExternalApiMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  void record(ExternalApiOperation operation, String result, Duration latency) {
    Timer.builder(METRIC_NAME)
        .description("외부 API 논리 호출 지연과 결과")
        .tags(
            "provider", operation.provider().name().toLowerCase(Locale.ROOT),
            "service", operation.serviceTag(),
            "operation", operation.operationTag(),
            "result", result)
        .register(registry)
        .record(latency);
  }
}
