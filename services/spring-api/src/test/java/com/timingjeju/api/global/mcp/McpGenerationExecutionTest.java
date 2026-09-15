package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class McpGenerationExecutionTest {
  @Test
  void 추천_응답_유실은_동일_worker_attempt에서_재호출하지_않는다() {
    var calls = new AtomicInteger();
    var resilience = McpCallResilience.defaults();
    assertThatThrownBy(
            () ->
                resilience.executeForTool(
                    "recommend_jeju_day_trips",
                    () -> {
                      calls.incrementAndGet();
                      throw new McpRemoteCallException("MCP_TIMEOUT", true);
                    }))
        .hasMessage("MCP_TIMEOUT");
    assertThat(calls).hasValue(1);
  }

  @Test
  void 일반_조회는_기존_재시도_정책을_유지한다() {
    var calls = new AtomicInteger();
    var resilience =
        new McpCallResilience(
            3, Duration.ZERO, 5, Duration.ofSeconds(30), System::nanoTime, ignored -> {});
    var result =
        resilience.executeForTool(
            "search_jeju_places",
            () -> {
              if (calls.incrementAndGet() < 3)
                throw new McpRemoteCallException("MCP_TIMEOUT", true);
              return "ok";
            });
    assertThat(result.value()).isEqualTo("ok");
    assertThat(result.attemptCount()).isEqualTo(3);
  }

  @Test
  void 요청제한시간은_AI_150초와_여유_15초를_수용한다() {
    assertThat(McpPrivateClientConfiguration.resolveRequestTimeout(null))
        .isEqualTo(Duration.ofSeconds(165));
    assertThatThrownBy(
            () -> McpPrivateClientConfiguration.resolveRequestTimeout(Duration.ofSeconds(35)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(McpPrivateClientConfiguration.resolveRequestTimeout(Duration.ofSeconds(180)))
        .isEqualTo(Duration.ofSeconds(180));
  }
}
