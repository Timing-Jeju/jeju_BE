package com.timingjeju.api.application.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SnapshotRetentionServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
  private static final Instant NANO_NOW = Instant.parse("2026-08-24T12:00:00.123456789Z");

  @Test
  void 한_batch는_Clock을_한번만_읽고_canonical_완료_공급자만_전달한다() {
    CountingClock clock = new CountingClock(NOW);
    RecordingPort port = new RecordingPort(result(3, 3, false));
    SnapshotRetentionService service = new SnapshotRetentionService(port, clock);

    SnapshotRetentionResult result = service.execute(false, 500);

    assertThat(result.purgedCount()).isEqualTo(3);
    assertThat(clock.calls()).isOne();
    assertThat(port.commands())
        .singleElement()
        .satisfies(
            command -> {
              assertThat(command.now()).isEqualTo(NOW);
              assertThat(command.providers()).containsExactly("TAGO", "kma", "tour-api");
              assertThat(command.batchSize()).isEqualTo(500);
              assertThat(command.dryRun()).isFalse();
              assertThatThrownBy(() -> command.providers().add("tmap"))
                  .isInstanceOf(UnsupportedOperationException.class);
            });
  }

  @Test
  void captured_now는_UTC_microsecond_floor로_한번만_정규화한다() {
    CountingClock clock = new CountingClock(NANO_NOW);
    RecordingPort port = new RecordingPort(result(0, 0, true));
    SnapshotRetentionService service = new SnapshotRetentionService(port, clock);

    service.execute(true, 1);

    assertThat(clock.calls()).isOne();
    assertThat(port.commands())
        .singleElement()
        .extracting(SnapshotRetentionCommand::now)
        .isEqualTo(Instant.parse("2026-08-24T12:00:00.123456Z"));
  }

  @Test
  void dry_run은_같은_command를_사용하고_candidate_count만_반환한다() {
    RecordingPort port = new RecordingPort(result(7, 0, true));
    SnapshotRetentionService service =
        new SnapshotRetentionService(port, Clock.fixed(NOW, ZoneOffset.UTC));

    SnapshotRetentionResult result = service.execute(true, 500);

    assertThat(result.candidateCount()).isEqualTo(7);
    assertThat(result.purgedCount()).isZero();
    assertThat(result.dryRun()).isTrue();
    assertThat(port.commands())
        .singleElement()
        .extracting(SnapshotRetentionCommand::dryRun)
        .isEqualTo(true);
  }

  @Test
  void batch_size_1과_500은_허용하고_범위_밖은_port_호출_전에_거부한다() {
    RecordingPort port = new RecordingPort(result(0, 0, true));
    SnapshotRetentionService service =
        new SnapshotRetentionService(port, Clock.fixed(NOW, ZoneOffset.UTC));

    service.execute(true, 1);
    service.execute(true, 500);

    assertThat(port.commands())
        .extracting(SnapshotRetentionCommand::batchSize)
        .containsExactly(1, 500);
    assertThatThrownBy(() -> service.execute(true, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.execute(true, 501))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(port.commands()).hasSize(2);
  }

  @Test
  void result는_count_duration_outcome_dryRun만_가진_immutable_value다() {
    SnapshotRetentionResult result = result(2, 2, false);

    assertThat(SnapshotRetentionResult.class.isRecord()).isTrue();
    assertThat(result)
        .extracting(
            SnapshotRetentionResult::candidateCount,
            SnapshotRetentionResult::purgedCount,
            SnapshotRetentionResult::duration,
            SnapshotRetentionResult::outcome,
            SnapshotRetentionResult::dryRun)
        .containsExactly(2, 2, Duration.ofMillis(4), SnapshotRetentionOutcome.SUCCESS, false);
  }

  @Test
  void typed_failure와_programmer_failure를_변형하거나_노출하지_않는다() {
    SnapshotRetentionException unavailable = SnapshotRetentionException.unavailable();
    SnapshotRetentionService unavailableService =
        new SnapshotRetentionService(
            command -> {
              throw unavailable;
            },
            Clock.fixed(NOW, ZoneOffset.UTC));
    IllegalStateException programmerFailure = new IllegalStateException("programmer bug");
    SnapshotRetentionService programmerService =
        new SnapshotRetentionService(
            command -> {
              throw programmerFailure;
            },
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> unavailableService.execute(true, 1))
        .isSameAs(unavailable)
        .hasMessage("SNAPSHOT_RETENTION_UNAVAILABLE")
        .hasNoCause();
    assertThatThrownBy(() -> programmerService.execute(true, 1)).isSameAs(programmerFailure);
  }

  private static SnapshotRetentionResult result(
      int candidateCount, int purgedCount, boolean dryRun) {
    return new SnapshotRetentionResult(
        candidateCount,
        purgedCount,
        Duration.ofMillis(4),
        SnapshotRetentionOutcome.SUCCESS,
        dryRun);
  }

  private static final class RecordingPort implements SnapshotRetentionPort {
    private final SnapshotRetentionResult result;
    private final List<SnapshotRetentionCommand> commands = new ArrayList<>();

    private RecordingPort(SnapshotRetentionResult result) {
      this.result = result;
    }

    @Override
    public SnapshotRetentionResult execute(SnapshotRetentionCommand command) {
      commands.add(command);
      return result;
    }

    private List<SnapshotRetentionCommand> commands() {
      return List.copyOf(commands);
    }
  }

  private static final class CountingClock extends Clock {
    private final Instant instant;
    private final AtomicInteger calls = new AtomicInteger();

    private CountingClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      calls.incrementAndGet();
      return instant;
    }

    private int calls() {
      return calls.get();
    }
  }
}
