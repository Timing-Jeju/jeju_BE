package com.timingjeju.api.application.commandinput.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

@Tag("unit")
class CommandLocationCleanupOrchestratorTest {

  @Test
  void full_500_batch는_계속하고_501건은_500과_1로_정리한다() {
    CommandLocationCleanupService service = mock(CommandLocationCleanupService.class);
    when(service.execute(ArgumentMatchers.eq(500), ArgumentMatchers.any(Duration.class)))
        .thenReturn(result(500))
        .thenReturn(result(1));

    CommandLocationCleanupCycleResult cycle =
        orchestrator(service, duration -> {}, ticker(0, 1_000_000))
            .execute(command(Duration.ofSeconds(30)));

    assertThat(cycle.batchCount()).isEqualTo(2);
    assertThat(cycle.redactedCount()).isEqualTo(501);
    assertThat(cycle.outcome()).isEqualTo(CommandLocationCleanupCycleOutcome.SUCCESS);
    verify(service, times(2)).execute(ArgumentMatchers.eq(500), ArgumentMatchers.any());
  }

  @Test
  void 매번_500건이어도_최대_10_batch와_5000건에서_멈춘다() {
    CommandLocationCleanupService service = mock(CommandLocationCleanupService.class);
    when(service.execute(ArgumentMatchers.eq(500), ArgumentMatchers.any(Duration.class)))
        .thenReturn(result(500));

    CommandLocationCleanupCycleResult cycle =
        orchestrator(service, duration -> {}, advancingTicker(Duration.ofMillis(1)))
            .execute(command(Duration.ofSeconds(30)));

    assertThat(cycle.batchCount()).isEqualTo(10);
    assertThat(cycle.redactedCount()).isEqualTo(5_000);
    assertThat(cycle.outcome()).isEqualTo(CommandLocationCleanupCycleOutcome.BOUNDED_BATCHES);
    verify(service, times(10)).execute(ArgumentMatchers.eq(500), ArgumentMatchers.any());
  }

  @Test
  void retryable_DB_failure는_정해진_backoff뒤_재시도하며_failure_code만_결과에_남긴다() {
    CommandLocationCleanupService service = mock(CommandLocationCleanupService.class);
    when(service.execute(ArgumentMatchers.eq(500), ArgumentMatchers.any(Duration.class)))
        .thenThrow(CommandLocationCleanupException.unavailable())
        .thenReturn(result(1));
    List<Duration> sleeps = new ArrayList<>();

    CommandLocationCleanupCycleResult cycle =
        orchestrator(service, sleeps::add, advancingTicker(Duration.ofMillis(10)))
            .execute(command(Duration.ofSeconds(30)));

    assertThat(cycle.outcome()).isEqualTo(CommandLocationCleanupCycleOutcome.SUCCESS);
    assertThat(cycle.attemptCount()).isEqualTo(2);
    assertThat(cycle.failureCode()).isNull();
    assertThat(sleeps).containsExactly(Duration.ofMillis(250));
  }

  @Test
  void 세번의_DB_failure는_raw_cause없이_분류_failureCode로_종료한다() {
    CommandLocationCleanupService service = mock(CommandLocationCleanupService.class);
    when(service.execute(ArgumentMatchers.eq(500), ArgumentMatchers.any(Duration.class)))
        .thenThrow(CommandLocationCleanupException.unavailable());

    CommandLocationCleanupCycleResult cycle =
        orchestrator(service, duration -> {}, advancingTicker(Duration.ofMillis(10)))
            .execute(command(Duration.ofSeconds(30)));

    assertThat(cycle.outcome()).isEqualTo(CommandLocationCleanupCycleOutcome.FAILED);
    assertThat(cycle.attemptCount()).isEqualTo(3);
    assertThat(cycle.failureCode())
        .isEqualTo(CommandLocationCleanupException.Code.COMMAND_LOCATION_CLEANUP_UNAVAILABLE);
    verify(service, times(3)).execute(ArgumentMatchers.eq(500), ArgumentMatchers.any());
  }

  @Test
  void maxDuration_999ms에는_남은_1ms_timeout으로_다음_batch를_시작한다() {
    CommandLocationCleanupService service = mock(CommandLocationCleanupService.class);
    FakeMonotonicClock clock = new FakeMonotonicClock();
    when(service.execute(ArgumentMatchers.eq(500), ArgumentMatchers.any(Duration.class)))
        .thenAnswer(
            invocation -> {
              clock.advance(Duration.ofMillis(999));
              return result(500);
            })
        .thenReturn(result(1));

    CommandLocationCleanupCycleResult cycle =
        orchestrator(service, clock::sleep, clock).execute(command(Duration.ofSeconds(1)));

    ArgumentCaptor<Duration> timeouts = ArgumentCaptor.forClass(Duration.class);
    verify(service, times(2)).execute(ArgumentMatchers.eq(500), timeouts.capture());
    assertThat(timeouts.getAllValues())
        .containsExactly(Duration.ofSeconds(1), Duration.ofMillis(1));
    assertThat(cycle.batchCount()).isEqualTo(2);
    assertThat(cycle.outcome()).isEqualTo(CommandLocationCleanupCycleOutcome.SUCCESS);
  }

  @Test
  void maxDuration_정확히_1초에는_추가_batch를_시작하지_않는다() {
    CommandLocationCleanupService service = mock(CommandLocationCleanupService.class);
    FakeMonotonicClock clock = new FakeMonotonicClock();
    when(service.execute(ArgumentMatchers.eq(500), ArgumentMatchers.any(Duration.class)))
        .thenAnswer(
            invocation -> {
              clock.advance(Duration.ofSeconds(1));
              return result(500);
            });

    CommandLocationCleanupCycleResult cycle =
        orchestrator(service, clock::sleep, clock).execute(command(Duration.ofSeconds(1)));

    assertThat(cycle.batchCount()).isOne();
    assertThat(cycle.outcome()).isEqualTo(CommandLocationCleanupCycleOutcome.BOUNDED_DURATION);
    assertThat(cycle.attemptCount()).isOne();
    verify(service).execute(500, Duration.ofSeconds(1));
  }

  @Test
  void retry_backoff는_남은_1ms로_cap되고_sleep직후_다음_DB_attempt없이_종료한다() {
    CommandLocationCleanupService service = mock(CommandLocationCleanupService.class);
    FakeMonotonicClock clock = new FakeMonotonicClock();
    when(service.execute(ArgumentMatchers.eq(500), ArgumentMatchers.any(Duration.class)))
        .thenAnswer(
            invocation -> {
              clock.advance(Duration.ofMillis(999));
              throw CommandLocationCleanupException.unavailable();
            });

    CommandLocationCleanupCycleResult cycle =
        orchestrator(service, clock::sleep, clock).execute(command(Duration.ofSeconds(1)));

    assertThat(cycle.outcome()).isEqualTo(CommandLocationCleanupCycleOutcome.BOUNDED_DURATION);
    assertThat(cycle.attemptCount()).isOne();
    assertThat(clock.sleeps()).containsExactly(Duration.ofMillis(1));
    verify(service).execute(500, Duration.ofSeconds(1));
  }

  private static CommandLocationCleanupCycleCommand command(Duration maxDuration) {
    return new CommandLocationCleanupCycleCommand(500, 10, 3, Duration.ofMillis(250), maxDuration);
  }

  private static CommandLocationCleanupOrchestrator orchestrator(
      CommandLocationCleanupService service,
      CommandLocationCleanupSleeper sleeper,
      LongSupplier ticker) {
    return new CommandLocationCleanupOrchestrator(service, sleeper, result -> {}, ticker);
  }

  private static CommandLocationCleanupResult result(int count) {
    return new CommandLocationCleanupResult(
        count, Duration.ofMillis(1), CommandLocationCleanupOutcome.SUCCESS);
  }

  private static LongSupplier ticker(long... values) {
    AtomicInteger index = new AtomicInteger();
    return () -> values[Math.min(index.getAndIncrement(), values.length - 1)];
  }

  private static LongSupplier advancingTicker(Duration step) {
    AtomicLong value = new AtomicLong();
    return () -> value.getAndAdd(step.toNanos());
  }

  private static final class FakeMonotonicClock implements LongSupplier {
    private final AtomicLong nanos = new AtomicLong();
    private final List<Duration> sleeps = new ArrayList<>();

    @Override
    public long getAsLong() {
      return nanos.get();
    }

    void advance(Duration duration) {
      nanos.addAndGet(duration.toNanos());
    }

    void sleep(Duration duration) {
      sleeps.add(duration);
      advance(duration);
    }

    List<Duration> sleeps() {
      return List.copyOf(sleeps);
    }
  }
}
