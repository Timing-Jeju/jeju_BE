package com.timingjeju.api.global.commandinput.cleanup;

import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupCycleMetrics;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupCycleResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Objects;

public final class CommandLocationCleanupMetrics implements CommandLocationCleanupCycleMetrics {
  private static final String PREFIX = "timingjeju.command.location.cleanup.";
  private final MeterRegistry registry;

  public CommandLocationCleanupMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry는 필수입니다.");
  }

  @Override
  public void record(CommandLocationCleanupCycleResult result) {
    Tags tags =
        Tags.of(
            "outcome",
            result.outcome().name().toLowerCase(Locale.ROOT),
            "failure_code",
            result.failureCode() == null ? "none" : result.failureCode().name());
    Counter.builder(PREFIX + "cycles").tags(tags).register(registry).increment();
    DistributionSummary.builder(PREFIX + "batches")
        .tags(tags)
        .register(registry)
        .record(result.batchCount());
    DistributionSummary.builder(PREFIX + "attempts")
        .tags(tags)
        .register(registry)
        .record(result.attemptCount());
    DistributionSummary.builder(PREFIX + "redacted")
        .tags(tags)
        .register(registry)
        .record(result.redactedCount());
    Timer.builder(PREFIX + "duration").tags(tags).register(registry).record(result.duration());
  }
}
