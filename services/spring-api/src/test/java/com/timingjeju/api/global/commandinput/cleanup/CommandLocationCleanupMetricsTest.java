package com.timingjeju.api.global.commandinput.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupCycleOutcome;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupCycleResult;
import com.timingjeju.api.application.commandinput.cleanup.CommandLocationCleanupException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class CommandLocationCleanupMetricsTest {
  @Test
  void metric은_고정된_outcome_failureCode와_count만_기록한다() {
    var registry = new SimpleMeterRegistry();
    var metrics = new CommandLocationCleanupMetrics(registry);

    metrics.record(
        new CommandLocationCleanupCycleResult(
            2, 3, 501, Duration.ofMillis(20), CommandLocationCleanupCycleOutcome.SUCCESS, null));

    assertThat(
            registry
                .get("timingjeju.command.location.cleanup.cycles")
                .tag("outcome", "success")
                .tag("failure_code", "none")
                .counter()
                .count())
        .isOne();
    assertThat(registry.getMetersAsString())
        .doesNotContain("gridX", "gridY", "coarse_location", "structured_input", "run_id");
  }

  @Test
  void 실패_metric은_closed_failureCode만_tag한다() {
    var registry = new SimpleMeterRegistry();
    var metrics = new CommandLocationCleanupMetrics(registry);

    metrics.record(
        new CommandLocationCleanupCycleResult(
            0,
            3,
            0,
            Duration.ofSeconds(1),
            CommandLocationCleanupCycleOutcome.FAILED,
            CommandLocationCleanupException.Code.COMMAND_LOCATION_CLEANUP_UNAVAILABLE));

    assertThat(
            registry
                .get("timingjeju.command.location.cleanup.cycles")
                .tag("outcome", "failed")
                .tag("failure_code", "COMMAND_LOCATION_CLEANUP_UNAVAILABLE")
                .counter()
                .count())
        .isOne();
  }
}
