package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

@Tag("unit")
class McpHealthIndicatorTest {

  @Test
  void schema_검증이_끝난_client만_health_UP이다() {
    JejuMcpClient readyClient = mock(JejuMcpClient.class);
    JejuMcpClient unavailableClient = mock(JejuMcpClient.class);
    when(readyClient.isReady()).thenReturn(true);
    when(unavailableClient.isReady()).thenReturn(false);
    McpHealthIndicator ready = new McpHealthIndicator(readyClient);
    McpHealthIndicator unavailable = new McpHealthIndicator(unavailableClient);

    assertThat(ready.health().getStatus()).isEqualTo(Status.UP);
    assertThat(unavailable.health().getStatus()).isEqualTo(Status.DOWN);
  }
}
