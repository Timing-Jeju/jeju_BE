package com.timingjeju.api.application.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class SnapshotRetentionOrchestratorTest {

  @Test
  void 성공한_500건_batch는_다음_batch를_이어가고_500미만이면_한_cycle을_종료한다() {
    SnapshotRetentionService service = mock(SnapshotRetentionService.class);
    when(service.execute(false, 500)).thenReturn(success(500, 500)).thenReturn(success(499, 499));
    SnapshotRetentionOrchestrator orchestrator = orchestrator(service, duration -> {});

    SnapshotRetentionCycleResult result =
        orchestrator.execute(
            new SnapshotRetentionCycleCommand(false, 500, 10, 3, Duration.ofMillis(250)));

    assertThat(result.batchCount()).isEqualTo(2);
    assertThat(result.candidateCount()).isEqualTo(999);
    assertThat(result.purgedCount()).isEqualTo(999);
    assertThat(result.outcome()).isEqualTo(SnapshotRetentionCycleOutcome.SUCCESS);
    verify(service, times(2)).execute(false, 500);
  }

  @Test
  void 모든_batch가_가득_차도_한_cycle은_10회와_5000건에서_멈춘다() {
    SnapshotRetentionService service = mock(SnapshotRetentionService.class);
    when(service.execute(false, 500)).thenReturn(success(500, 500));
    SnapshotRetentionOrchestrator orchestrator = orchestrator(service, duration -> {});

    SnapshotRetentionCycleResult result =
        orchestrator.execute(command(false, 10, 3, Duration.ofMillis(250)));

    assertThat(result.batchCount()).isEqualTo(10);
    assertThat(result.purgedCount()).isEqualTo(5_000);
    assertThat(result.outcome()).isEqualTo(SnapshotRetentionCycleOutcome.BOUNDED);
    verify(service, times(10)).execute(false, 500);
  }

  @Test
  void dry_run은_candidate가_가득_차도_정확히_한_batch만_실행한다() {
    SnapshotRetentionService service = mock(SnapshotRetentionService.class);
    when(service.execute(true, 500))
        .thenReturn(
            new SnapshotRetentionResult(
                500, 0, Duration.ofMillis(5), SnapshotRetentionOutcome.SUCCESS, true));
    SnapshotRetentionOrchestrator orchestrator = orchestrator(service, duration -> {});

    SnapshotRetentionCycleResult result =
        orchestrator.execute(command(true, 10, 3, Duration.ofMillis(250)));

    assertThat(result.batchCount()).isOne();
    assertThat(result.candidateCount()).isEqualTo(500);
    assertThat(result.purgedCount()).isZero();
    verify(service).execute(true, 500);
  }

  @Test
  void retryable_DB_failure_두번은_250ms와_500ms_backoff_후_세번째_attempt로_성공한다() {
    SnapshotRetentionService service = mock(SnapshotRetentionService.class);
    when(service.execute(false, 500))
        .thenThrow(SnapshotRetentionException.unavailable())
        .thenThrow(SnapshotRetentionException.unavailable())
        .thenReturn(success(1, 1));
    List<Duration> sleeps = new ArrayList<>();
    SnapshotRetentionOrchestrator orchestrator =
        orchestrator(service, sleeps::add, ticker(1_000_000_000L, 1_900_000_000L));

    SnapshotRetentionCycleResult result =
        orchestrator.execute(command(false, 10, 3, Duration.ofMillis(250)));

    assertThat(result.outcome()).isEqualTo(SnapshotRetentionCycleOutcome.SUCCESS);
    assertThat(result.attemptCount()).isEqualTo(3);
    assertThat(result.duration()).isEqualTo(Duration.ofMillis(900));
    assertThat(sleeps).containsExactly(Duration.ofMillis(250), Duration.ofMillis(500));
    verify(service, times(3)).execute(false, 500);
  }

  @Test
  void retryable_DB_failure가_세번이면_stable_failed로_종료하고_추가_attempt를_막는다() {
    SnapshotRetentionService service = mock(SnapshotRetentionService.class);
    when(service.execute(false, 500)).thenThrow(SnapshotRetentionException.unavailable());
    SnapshotRetentionOrchestrator orchestrator =
        orchestrator(service, duration -> {}, ticker(2_000_000_000L, 3_250_000_000L));

    SnapshotRetentionCycleResult result =
        orchestrator.execute(command(false, 10, 3, Duration.ofMillis(250)));

    assertThat(result.outcome()).isEqualTo(SnapshotRetentionCycleOutcome.FAILED);
    assertThat(result.attemptCount()).isEqualTo(3);
    assertThat(result.duration()).isEqualTo(Duration.ofMillis(1_250));
    verify(service, times(3)).execute(false, 500);
  }

  @Test
  void nonretryable_domain과_programmer_exception은_원형으로_전파하고_재시도하지_않는다() {
    SnapshotRetentionService domainService = mock(SnapshotRetentionService.class);
    IllegalArgumentException domainFailure = new IllegalArgumentException("non-retryable");
    when(domainService.execute(false, 500)).thenThrow(domainFailure);
    SnapshotRetentionService programmerService = mock(SnapshotRetentionService.class);
    IllegalStateException programmerFailure =
        new IllegalStateException("secret SQL should not log");
    when(programmerService.execute(false, 500)).thenThrow(programmerFailure);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                orchestrator(domainService, duration -> {})
                    .execute(command(false, 10, 3, Duration.ofMillis(250))))
        .isSameAs(domainFailure);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                orchestrator(programmerService, duration -> {})
                    .execute(command(false, 10, 3, Duration.ofMillis(250))))
        .isSameAs(programmerFailure);
    verify(domainService).execute(false, 500);
    verify(programmerService).execute(false, 500);
  }

  @Test
  void interrupt는_flag를_복원하고_backoff뒤_추가_DB_action없이_즉시_중단한다() {
    SnapshotRetentionService service = mock(SnapshotRetentionService.class);
    when(service.execute(false, 500)).thenThrow(SnapshotRetentionException.unavailable());
    SnapshotRetentionOrchestrator orchestrator =
        orchestrator(
            service,
            duration -> {
              throw new InterruptedException("stop");
            },
            ticker(4_000_000_000L, 4_400_000_000L));

    try {
      SnapshotRetentionCycleResult result =
          orchestrator.execute(command(false, 10, 3, Duration.ofMillis(250)));

      assertThat(result.outcome()).isEqualTo(SnapshotRetentionCycleOutcome.INTERRUPTED);
      assertThat(result.duration()).isEqualTo(Duration.ofMillis(400));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      verify(service).execute(false, 500);
      verify(service, never()).execute(true, 500);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void 성공_cycle_duration은_batch_duration_합이_아니라_monotonic_전체_경계를_측정한다() {
    SnapshotRetentionService service = mock(SnapshotRetentionService.class);
    when(service.execute(false, 500)).thenReturn(success(1, 1));
    List<SnapshotRetentionCycleResult> published = new ArrayList<>();
    SnapshotRetentionOrchestrator orchestrator =
        new SnapshotRetentionOrchestrator(
            service, duration -> {}, published::add, ticker(10_000_000_000L, 10_750_000_000L));

    SnapshotRetentionCycleResult result =
        orchestrator.execute(command(false, 10, 3, Duration.ofMillis(250)));

    assertThat(result.duration()).isEqualTo(Duration.ofMillis(750));
    assertThat(published).containsExactly(result);
  }

  private static SnapshotRetentionCycleCommand command(
      boolean dryRun, int maxBatches, int retryAttempts, Duration initialBackoff) {
    return new SnapshotRetentionCycleCommand(
        dryRun, 500, maxBatches, retryAttempts, initialBackoff);
  }

  private static SnapshotRetentionOrchestrator orchestrator(
      SnapshotRetentionService service, SnapshotRetentionSleeper sleeper) {
    return orchestrator(service, sleeper, System::nanoTime);
  }

  private static SnapshotRetentionOrchestrator orchestrator(
      SnapshotRetentionService service,
      SnapshotRetentionSleeper sleeper,
      LongSupplier monotonicTime) {
    return new SnapshotRetentionOrchestrator(service, sleeper, result -> {}, monotonicTime);
  }

  private static LongSupplier ticker(long... values) {
    java.util.concurrent.atomic.AtomicInteger index =
        new java.util.concurrent.atomic.AtomicInteger();
    return () -> values[index.getAndIncrement()];
  }

  private static SnapshotRetentionResult success(int candidates, int purged) {
    return new SnapshotRetentionResult(
        candidates, purged, Duration.ofMillis(5), SnapshotRetentionOutcome.SUCCESS, false);
  }
}
