package com.timingjeju.api.domain.accountdeletion.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DeletionWorkerPolicyTest {
  @Test
  void retry는_exponential_backoff를_cap하고_jitter경계를_적용한다() {
    DeletionWorkerPolicy policy =
        new DeletionWorkerPolicy(
            Duration.ofSeconds(30), 10, 5, Duration.ofSeconds(2), Duration.ofSeconds(10));

    assertThat(policy.retryDelay(1, () -> 0.0d)).isZero();
    assertThat(policy.retryDelay(2, () -> 0.5d)).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.retryDelay(31, () -> 1.0d)).isEqualTo(Duration.ofSeconds(10));

    Duration huge = Duration.ofMillis(Long.MAX_VALUE / 2);
    DeletionWorkerPolicy overflowSafe =
        new DeletionWorkerPolicy(Duration.ofSeconds(1), 1, 5, huge, huge);
    assertThat(overflowSafe.retryDelay(3, () -> 1.0d)).isEqualTo(huge);
  }

  @Test
  void lease_attempt_jitter_retry범위가_잘못되면_즉시_거부한다() {
    assertThatThrownBy(
            () ->
                new DeletionWorkerPolicy(Duration.ZERO, 1, 1, Duration.ZERO, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DeletionWorkerPolicy(
                    Duration.ofSeconds(1), 0, 1, Duration.ZERO, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DeletionWorkerPolicy(
                    Duration.ofSeconds(1), 1, 1, Duration.ofSeconds(2), Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);

    DeletionWorkerPolicy policy = DeletionWorkerPolicy.defaults();
    assertThatThrownBy(() -> policy.retryDelay(0, () -> 0.5d))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.retryDelay(1, () -> -0.1d))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> policy.retryDelay(1, () -> 1.1d))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
