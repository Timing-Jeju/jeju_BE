package com.timingjeju.api.global.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.retention.SnapshotRetentionCycleOutcome;
import com.timingjeju.api.application.retention.SnapshotRetentionCycleResult;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SnapshotRetentionMetricsTest {

  @Test
  void metric은_고정된_이름과_mode_outcome_tag만_사용하고_count_duration을_기록한다() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SnapshotRetentionMetrics metrics = new SnapshotRetentionMetrics(registry);

    metrics.record(
        new SnapshotRetentionCycleResult(
            2, 3, 999, 999, Duration.ofMillis(20), SnapshotRetentionCycleOutcome.SUCCESS, false));

    assertThat(registry.get("timingjeju.snapshot.retention.cycles").counter().count())
        .isEqualTo(1.0);
    assertThat(registry.get("timingjeju.snapshot.retention.batches").summary().totalAmount())
        .isEqualTo(2.0);
    assertThat(registry.get("timingjeju.snapshot.retention.attempts").summary().totalAmount())
        .isEqualTo(3.0);
    assertThat(registry.get("timingjeju.snapshot.retention.candidates").summary().totalAmount())
        .isEqualTo(999.0);
    assertThat(registry.get("timingjeju.snapshot.retention.purged").summary().totalAmount())
        .isEqualTo(999.0);
    assertThat(registry.get("timingjeju.snapshot.retention.duration").timer().count()).isOne();

    Set<String> tagKeys =
        registry.getMeters().stream()
            .flatMap(meter -> meter.getId().getTags().stream())
            .map(io.micrometer.core.instrument.Tag::getKey)
            .collect(Collectors.toSet());
    assertThat(tagKeys).containsOnly("mode", "outcome");
  }

  @Test
  void metric에는_provider_id_scope_error_message_SQL_URL_token을_태그나_값으로_넣지_않는다() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    SnapshotRetentionMetrics metrics = new SnapshotRetentionMetrics(registry);

    metrics.record(
        new SnapshotRetentionCycleResult(
            1, 3, 0, 0, Duration.ZERO, SnapshotRetentionCycleOutcome.FAILED, true));

    String boundedMetadata =
        registry.getMeters().stream()
            .map(Meter::getId)
            .flatMap(id -> id.getTags().stream())
            .map(tag -> tag.getKey() + "=" + tag.getValue())
            .collect(Collectors.joining("\n"));
    assertThat(boundedMetadata)
        .doesNotContain(
            "provider",
            "service",
            "operation",
            "snapshot",
            "scope",
            "exception",
            "message",
            "sql",
            "url",
            "query",
            "token",
            "TAGO",
            "tour-api",
            "kma");
  }
}
