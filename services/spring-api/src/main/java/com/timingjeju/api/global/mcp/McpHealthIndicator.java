package com.timingjeju.api.global.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("jejuPlannerMcp")
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true")
public final class McpHealthIndicator implements HealthIndicator {
  private final JejuMcpClient client;

  public McpHealthIndicator(JejuMcpClient client) {
    this.client = client;
  }

  @Override
  public Health health() {
    return client.isReady() ? Health.up().build() : Health.down().build();
  }
}
