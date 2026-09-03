package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class McpCallResilienceTest {

  @Test
  void transport_오류는_제한_재시도하고_성공_attempt를_반환한다() {
    AtomicInteger calls = new AtomicInteger();
    McpCallResilience resilience = resilience(new AtomicLong(), duration -> {});

    McpResilientResult<String> result =
        resilience.execute(
            () -> {
              if (calls.incrementAndGet() < 3) throw new IllegalStateException("transport");
              return "ok";
            });

    assertThat(result.value()).isEqualTo("ok");
    assertThat(result.attemptCount()).isEqualTo(3);
    assertThat(calls).hasValue(3);
  }

  @Test
  void contract_오류는_재시도하지_않는다() {
    AtomicInteger calls = new AtomicInteger();
    McpCallResilience resilience = resilience(new AtomicLong(), duration -> {});

    assertThatThrownBy(
            () ->
                resilience.execute(
                    () -> {
                      calls.incrementAndGet();
                      throw new McpContractException("MCP_CONTRACT_INVALID");
                    }))
        .isInstanceOf(McpContractException.class);
    assertThat(calls).hasValue(1);
  }

  @Test
  void 연속_실패가_임계값에_도달하면_open하고_시간_뒤_half_open을_허용한다() {
    AtomicLong now = new AtomicLong();
    AtomicInteger calls = new AtomicInteger();
    McpCallResilience resilience = resilience(now, duration -> {});

    for (int index = 0; index < 2; index++) {
      assertThatThrownBy(
              () ->
                  resilience.execute(
                      () -> {
                        calls.incrementAndGet();
                        throw new IllegalStateException("transport");
                      }))
          .isInstanceOf(IllegalStateException.class);
    }
    assertThatThrownBy(() -> resilience.execute(() -> "blocked"))
        .isInstanceOf(McpRemoteCallException.class)
        .hasMessage("MCP_CIRCUIT_OPEN");
    assertThat(calls).hasValue(6);

    now.addAndGet(Duration.ofSeconds(31).toNanos());
    McpResilientResult<String> recovered = resilience.execute(() -> "recovered");

    assertThat(recovered.value()).isEqualTo("recovered");
    assertThat(recovered.attemptCount()).isEqualTo(1);
  }

  @Test
  void open_이전_inflight_성공은_open_circuit을_닫지_못한다() {
    AtomicLong now = new AtomicLong();
    McpCallResilience resilience =
        new McpCallResilience(
            1, Duration.ZERO, 1, Duration.ofSeconds(30), now::get, duration -> {});

    McpResilientResult<String> stale =
        resilience.execute(
            () -> {
              assertThatThrownBy(
                      () ->
                          resilience.execute(
                              () -> {
                                throw new McpRemoteCallException("MCP_TIMEOUT", false);
                              }))
                  .isInstanceOf(McpRemoteCallException.class)
                  .hasMessage("MCP_TIMEOUT");
              return "stale-success";
            });

    assertThat(stale.value()).isEqualTo("stale-success");
    assertThatThrownBy(() -> resilience.execute(() -> "must-remain-open"))
        .isInstanceOf(McpRemoteCallException.class)
        .hasMessage("MCP_CIRCUIT_OPEN");
  }

  private static McpCallResilience resilience(AtomicLong now, McpCallResilience.Sleeper sleeper) {
    return new McpCallResilience(
        3, Duration.ofMillis(10), 2, Duration.ofSeconds(30), now::get, sleeper);
  }
}
