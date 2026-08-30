package com.timingjeju.api.global.push.firebase;

import com.timingjeju.api.application.push.PushErrorClass;
import com.timingjeju.api.application.push.PushSendResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FirebasePushTelemetry {
  static final String METRIC_NAME = "timingjeju.push.provider.requests";
  private static final Logger log = LoggerFactory.getLogger(FirebasePushTelemetry.class);

  private final MeterRegistry registry;
  private final Clock clock;

  FirebasePushTelemetry(MeterRegistry registry, Clock clock) {
    this.registry = registry;
    this.clock = clock;
  }

  Instant start() {
    return clock.instant();
  }

  void record(PushSendResult result, Instant startedAt) {
    PushErrorClass errorClass = errorClass(result);
    String outcome = outcome(result);
    Timer.builder(METRIC_NAME)
        .tags(
            "provider",
            "firebase",
            "outcome",
            outcome,
            "errorClass",
            errorClass == null ? "none" : errorClass.name().toLowerCase(Locale.ROOT))
        .register(registry)
        .record(Duration.between(startedAt, clock.instant()));
    log.info(
        "push_provider_result provider=firebase outcome={} errorClass={}",
        outcome,
        errorClass == null ? "none" : errorClass);
  }

  private static String outcome(PushSendResult result) {
    if (result instanceof PushSendResult.Accepted) return "accepted";
    if (result instanceof PushSendResult.RetryableFailure) return "retryable_failure";
    if (result instanceof PushSendResult.AcceptanceUnknown) return "acceptance_unknown";
    return "permanent_failure";
  }

  private static PushErrorClass errorClass(PushSendResult result) {
    if (result instanceof PushSendResult.RetryableFailure retryable) return retryable.errorClass();
    if (result instanceof PushSendResult.AcceptanceUnknown unknown) return unknown.errorClass();
    if (result instanceof PushSendResult.PermanentFailure permanent) return permanent.errorClass();
    return null;
  }
}
