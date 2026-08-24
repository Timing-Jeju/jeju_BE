package com.timingjeju.api.global.retention;

import com.timingjeju.api.application.retention.SnapshotRetentionCycleMetrics;
import com.timingjeju.api.application.retention.SnapshotRetentionCycleResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;

public final class SnapshotRetentionMetrics implements SnapshotRetentionCycleMetrics {
  private static final String PREFIX = "timingjeju.snapshot.retention.";
  private final MeterRegistry registry;

  public SnapshotRetentionMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry는 필수입니다.");
  }

  @Override
  public void record(SnapshotRetentionCycleResult result) {
    Tags tags =
        Tags.of(
            "mode",
            result.dryRun() ? "dry-run" : "purge",
            "outcome",
            result.outcome().name().toLowerCase(java.util.Locale.ROOT));
    Counter.builder(PREFIX + "cycles").tags(tags).register(registry).increment();
    DistributionSummary.builder(PREFIX + "batches")
        .tags(tags)
        .register(registry)
        .record(result.batchCount());
    DistributionSummary.builder(PREFIX + "attempts")
        .tags(tags)
        .register(registry)
        .record(result.attemptCount());
    DistributionSummary.builder(PREFIX + "candidates")
        .tags(tags)
        .register(registry)
        .record(result.candidateCount());
    DistributionSummary.builder(PREFIX + "purged")
        .tags(tags)
        .register(registry)
        .record(result.purgedCount());
    Timer.builder(PREFIX + "duration").tags(tags).register(registry).record(result.duration());
  }
}
