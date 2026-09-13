package com.timingjeju.api.application.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GenerationExecutionPolicyTest {
  @Test
  void 생성과_세번_검증_및_저장시간을_실행예산에_포함한다() {
    var policy = GenerationExecutionPolicy.forRequestTimeout(Duration.ofSeconds(165));
    assertThat(policy.leaseDuration()).isEqualTo(Duration.ofSeconds(180));
    assertThat(policy.executionDeadline()).isEqualTo(Duration.ofSeconds(675));
    assertThat(policy.heartbeatInterval()).isEqualTo(Duration.ofSeconds(10));
    assertThat(policy.claimBatchSize()).isEqualTo(1);
  }

  @Test
  void AI_예산보다_짧은_설정은_거부한다() {
    assertThatThrownBy(() -> GenerationExecutionPolicy.forRequestTimeout(Duration.ofSeconds(164)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
