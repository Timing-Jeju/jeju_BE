package com.timingjeju.api.application.asyncrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ThreadedRunExecutionSupervisorTest {

  private static final RunLease LEASE =
      new RunLease(UUID.fromString("74000000-0000-0000-0000-000000000099"), 1, 1);

  @Test
  void concurrency는_1에서_50_사이여야_한다() {
    Clock clock = Clock.systemUTC();

    assertThatThrownBy(() -> ThreadedRunExecutionSupervisor.create(clock, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ThreadedRunExecutionSupervisor.create(clock, 51))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void heartbeat_interval은_양수여야_한다() {
    ThreadedRunExecutionSupervisor supervisor =
        ThreadedRunExecutionSupervisor.create(Clock.systemUTC(), 1);

    try {
      assertThatThrownBy(
              () ->
                  supervisor.supervise(
                      LEASE,
                      Instant.now().plusSeconds(1),
                      Duration.ZERO,
                      (lease, ignored) -> RunResultSource.COMPUTED,
                      () -> true))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () ->
                  supervisor.supervise(
                      LEASE,
                      Instant.now().plusSeconds(1),
                      null,
                      (lease, ignored) -> RunResultSource.COMPUTED,
                      () -> true))
          .isInstanceOf(IllegalArgumentException.class);
    } finally {
      supervisor.shutdown(Duration.ZERO);
    }
  }

  @Test
  void 이미_지난_deadline은_실행을_시작하지_않고_retryable_실패를_반환한다() {
    ThreadedRunExecutionSupervisor supervisor =
        ThreadedRunExecutionSupervisor.create(Clock.systemUTC(), 1);
    AtomicInteger executions = new AtomicInteger();

    try {
      assertThatThrownBy(
              () ->
                  supervisor
                      .supervise(
                          LEASE,
                          Instant.now().minusMillis(1),
                          Duration.ofMillis(10),
                          (lease, ignored) -> {
                            executions.incrementAndGet();
                            return RunResultSource.COMPUTED;
                          },
                          () -> true)
                      .join())
          .hasRootCauseInstanceOf(RetryableRunException.class)
          .rootCause()
          .extracting("stableErrorCode")
          .isEqualTo("ASYNC_RUN_DEADLINE_EXCEEDED");
      assertThat(executions).hasValue(0);
    } finally {
      supervisor.shutdown(Duration.ZERO);
    }
  }

  @Test
  void executor_예외는_completion_failure로_전파한다() {
    ThreadedRunExecutionSupervisor supervisor =
        ThreadedRunExecutionSupervisor.create(Clock.systemUTC(), 1);

    try {
      assertThatThrownBy(
              () ->
                  supervisor
                      .supervise(
                          LEASE,
                          Instant.now().plusSeconds(1),
                          Duration.ofMillis(50),
                          (lease, ignored) -> {
                            throw new IllegalStateException("boom");
                          },
                          () -> true)
                      .join())
          .hasRootCauseInstanceOf(IllegalStateException.class);
    } finally {
      supervisor.shutdown(Duration.ofSeconds(1));
    }
  }

  @Test
  void heartbeat_예외는_lease_lost로_전환하고_실행을_interrupt한다() {
    ThreadedRunExecutionSupervisor supervisor =
        ThreadedRunExecutionSupervisor.create(Clock.systemUTC(), 1);
    CountDownLatch interrupted = new CountDownLatch(1);

    try {
      assertThatThrownBy(
              () ->
                  supervisor
                      .supervise(
                          LEASE,
                          Instant.now().plusSeconds(2),
                          Duration.ofMillis(10),
                          (lease, ignored) -> {
                            try {
                              Thread.sleep(5_000);
                            } catch (InterruptedException exception) {
                              interrupted.countDown();
                              Thread.currentThread().interrupt();
                            }
                            return RunResultSource.COMPUTED;
                          },
                          () -> {
                            throw new IllegalStateException("heartbeat store unavailable");
                          })
                      .join())
          .hasRootCauseInstanceOf(RunLeaseLostException.class);
      assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    } finally {
      supervisor.shutdown(Duration.ofSeconds(1));
    }
  }

  @Test
  void shutdown은_idempotent하고_이후_새_실행을_거부한다() {
    ThreadedRunExecutionSupervisor supervisor =
        ThreadedRunExecutionSupervisor.create(Clock.systemUTC(), 1);

    supervisor.shutdown(Duration.ZERO);
    supervisor.shutdown(Duration.ZERO);

    assertThatThrownBy(
            () ->
                supervisor.supervise(
                    LEASE,
                    Instant.now().plusSeconds(1),
                    Duration.ofMillis(10),
                    (lease, ignored) -> RunResultSource.COMPUTED,
                    () -> true))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void 장기_실행중에는_heartbeat하고_deadline에서_interrupt한다() {
    ThreadedRunExecutionSupervisor supervisor =
        ThreadedRunExecutionSupervisor.create(Clock.systemUTC(), 1);
    AtomicInteger heartbeats = new AtomicInteger();
    CountDownLatch interrupted = new CountDownLatch(1);
    Instant deadline = Instant.now().plusMillis(150);

    try {
      assertThatThrownBy(
              () ->
                  supervisor
                      .supervise(
                          LEASE,
                          deadline,
                          Duration.ofMillis(20),
                          (lease, ignored) -> {
                            try {
                              Thread.sleep(5_000);
                            } catch (InterruptedException exception) {
                              interrupted.countDown();
                              Thread.currentThread().interrupt();
                            }
                            return RunResultSource.COMPUTED;
                          },
                          () -> {
                            heartbeats.incrementAndGet();
                            return true;
                          })
                      .join())
          .hasRootCauseInstanceOf(RetryableRunException.class)
          .rootCause()
          .extracting("stableErrorCode")
          .isEqualTo("ASYNC_RUN_DEADLINE_EXCEEDED");
      assertThat(heartbeats.get()).isPositive();
      assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(exception);
    } finally {
      supervisor.shutdown(Duration.ofSeconds(1));
    }
  }

  @Test
  void heartbeat이_fencing_상실을_알리면_실행을_interrupt하고_terminal_쓰기를_중단한다() {
    ThreadedRunExecutionSupervisor supervisor =
        ThreadedRunExecutionSupervisor.create(Clock.systemUTC(), 1);

    try {
      assertThatThrownBy(
              () ->
                  supervisor
                      .supervise(
                          LEASE,
                          Instant.now().plusSeconds(2),
                          Duration.ofMillis(10),
                          (lease, ignored) -> {
                            try {
                              Thread.sleep(5_000);
                            } catch (InterruptedException exception) {
                              Thread.currentThread().interrupt();
                            }
                            return RunResultSource.COMPUTED;
                          },
                          () -> false)
                      .join())
          .hasRootCauseInstanceOf(RunLeaseLostException.class);
    } finally {
      supervisor.shutdown(Duration.ofSeconds(1));
    }
  }
}
