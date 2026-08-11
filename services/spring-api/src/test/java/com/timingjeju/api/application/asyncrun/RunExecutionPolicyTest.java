package com.timingjeju.api.application.asyncrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class RunExecutionPolicyTest {

  @Test
  void 기본_정책은_issue_74의_운영_상수를_고정한다() {
    RunExecutionPolicy policy = RunExecutionPolicy.defaults();

    assertThat(policy.leaseDuration()).isEqualTo(Duration.ofSeconds(30));
    assertThat(policy.heartbeatInterval()).isEqualTo(Duration.ofSeconds(10));
    assertThat(policy.claimBatchSize()).isEqualTo(50);
    assertThat(policy.maxAttempts()).isEqualTo(5);
    assertThat(policy.backoffBase()).isEqualTo(Duration.ofSeconds(1));
    assertThat(policy.backoffCap()).isEqualTo(Duration.ofSeconds(60));
    assertThat(policy.executionDeadline()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void full_jitter는_exponential_backoff_범위_안에서_결정된다() {
    RunExecutionPolicy policy = RunExecutionPolicy.defaults();

    assertThat(policy.retryDelay(1, () -> 0.0d)).isZero();
    assertThat(policy.retryDelay(1, () -> Math.nextDown(1.0d))).isLessThan(Duration.ofSeconds(1));
    assertThat(policy.retryDelay(5, () -> 0.5d)).isEqualTo(Duration.ofSeconds(8));
    assertThat(policy.retryDelay(20, () -> Math.nextDown(1.0d))).isLessThan(Duration.ofSeconds(60));
  }

  @Test
  void heartbeat은_lease보다_짧고_정책값은_양수여야_한다() {
    assertThatThrownBy(
            () ->
                new RunExecutionPolicy(
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(10),
                    50,
                    5,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
