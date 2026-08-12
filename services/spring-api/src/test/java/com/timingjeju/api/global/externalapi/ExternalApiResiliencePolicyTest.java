package com.timingjeju.api.global.externalapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ExternalApiResiliencePolicyTest {

  @Test
  void 공통_복원력_기본값을_계약대로_고정한다() {
    ExternalApiResiliencePolicy policy = ExternalApiResiliencePolicy.defaults();

    assertThat(policy.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.readTimeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(policy.totalTimeout()).isEqualTo(Duration.ofSeconds(8));
    assertThat(policy.maxAttempts()).isEqualTo(3);
    assertThat(policy.retryBaseDelay()).isEqualTo(Duration.ofMillis(200));
    assertThat(policy.retryDelayCap()).isEqualTo(Duration.ofSeconds(2));
    assertThat(policy.retryAfterCap()).isEqualTo(Duration.ofSeconds(5));
    assertThat(policy.maximumDecompressedBodyBytes()).isEqualTo(2L * 1024L * 1024L);
    assertThat(policy.circuitWindowSize()).isEqualTo(20);
    assertThat(policy.circuitMinimumCalls()).isEqualTo(10);
    assertThat(policy.circuitFailureRate()).isEqualTo(0.5);
    assertThat(policy.circuitOpenDuration()).isEqualTo(Duration.ofSeconds(30));
    assertThat(policy.circuitHalfOpenCalls()).isEqualTo(3);
  }

  @Test
  void full_jitter는_시도별_상한과_2초_cap_안에서만_지연을_선택한다() {
    SequenceJitter jitter = new SequenceJitter(199, 399, 799, 1_999);
    ExternalApiResiliencePolicy policy = ExternalApiResiliencePolicy.defaults();

    assertThat(policy.retryDelay(1, jitter)).isEqualTo(Duration.ofMillis(199));
    assertThat(policy.retryDelay(2, jitter)).isEqualTo(Duration.ofMillis(399));
    assertThat(policy.retryDelay(3, jitter)).isEqualTo(Duration.ofMillis(799));
    assertThat(policy.retryDelay(8, jitter)).isEqualTo(Duration.ofMillis(1_999));
    assertThat(jitter.requestedUpperBounds()).containsExactly(200L, 400L, 800L, 2_000L);
  }

  @Test
  void circuit는_최소_10회와_50퍼센트_실패에서_30초_open후_3회_half_open을_적용한다() {
    MutableTimeSource time = new MutableTimeSource();
    ExternalApiCircuitBreaker circuit =
        new ExternalApiCircuitBreaker(ExternalApiResiliencePolicy.defaults(), time);

    for (int index = 0; index < 9; index++) {
      ExternalApiCircuitBreaker.Permit permit = circuit.acquire();
      circuit.record(permit, index < 5);
    }
    assertThat(circuit.state()).isEqualTo(ExternalApiCircuitBreaker.State.CLOSED);

    ExternalApiCircuitBreaker.Permit tenth = circuit.acquire();
    circuit.record(tenth, true);
    assertThat(circuit.state()).isEqualTo(ExternalApiCircuitBreaker.State.OPEN);
    assertThatThrownBy(circuit::acquire).isInstanceOf(ExternalApiCircuitOpenException.class);

    time.advance(Duration.ofSeconds(29));
    assertThatThrownBy(circuit::acquire).isInstanceOf(ExternalApiCircuitOpenException.class);
    time.advance(Duration.ofSeconds(1));

    for (int index = 0; index < 3; index++) {
      ExternalApiCircuitBreaker.Permit permit = circuit.acquire();
      assertThat(permit.halfOpen()).isTrue();
      circuit.record(permit, false);
    }
    assertThat(circuit.state()).isEqualTo(ExternalApiCircuitBreaker.State.CLOSED);
  }

  @Test
  void half_open에서_한번이라도_실패하면_즉시_다시_open한다() {
    MutableTimeSource time = new MutableTimeSource();
    ExternalApiCircuitBreaker circuit =
        new ExternalApiCircuitBreaker(ExternalApiResiliencePolicy.defaults(), time);
    for (int index = 0; index < 10; index++) {
      ExternalApiCircuitBreaker.Permit permit = circuit.acquire();
      circuit.record(permit, true);
    }
    time.advance(Duration.ofSeconds(30));

    ExternalApiCircuitBreaker.Permit permit = circuit.acquire();
    circuit.record(permit, true);

    assertThat(circuit.state()).isEqualTo(ExternalApiCircuitBreaker.State.OPEN);
  }

  private static final class SequenceJitter implements ExternalApiJitter {
    private final Deque<Long> values;
    private final Deque<Long> upperBounds = new ArrayDeque<>();

    private SequenceJitter(long... values) {
      this.values = new ArrayDeque<>();
      for (long value : values) {
        this.values.add(value);
      }
    }

    @Override
    public long nextLong(long exclusiveUpperBound) {
      upperBounds.add(exclusiveUpperBound);
      return values.removeFirst();
    }

    private Deque<Long> requestedUpperBounds() {
      return upperBounds;
    }
  }

  static final class MutableTimeSource implements ExternalApiTimeSource {
    private Instant instant = Instant.parse("2026-08-12T00:00:00Z");
    private long nanos;

    @Override
    public Instant now() {
      return instant;
    }

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public void sleep(Duration duration) {
      advance(duration);
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
      nanos += duration.toNanos();
    }
  }
}
