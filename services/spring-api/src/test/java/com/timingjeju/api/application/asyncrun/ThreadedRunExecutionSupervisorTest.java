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
                          },
                          () -> false)
                      .join())
          .hasRootCauseInstanceOf(RunLeaseLostException.class);
    } finally {
      supervisor.shutdown(Duration.ofSeconds(1));
    }
  }
}
