package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
@SpringBootTest(
    properties = {
      "app.mcp.enabled=true",
      "app.mcp.base-url=https://127.0.0.1:18443",
      "app.mcp.allowed-host=127.0.0.1"
    })
@EnabledIfEnvironmentVariable(named = "MCP_LIVE_TEST", matches = "true")
class McpPrivateHttpIntegrationTest {

  @Autowired private McpToolClient client;

  @DynamicPropertySource
  static void privateMcpProperties(DynamicPropertyRegistry registry) {
    registry.add("app.mcp.issuer", () -> requiredEnvironment("MCP_JWT_ISSUER"));
    registry.add("app.mcp.audience", () -> requiredEnvironment("MCP_JWT_AUDIENCE"));
    registry.add("app.mcp.subject", () -> "backend-worker");
    registry.add("app.mcp.scope", () -> "jeju:mcp:invoke");
    registry.add(
        "app.mcp.signing-key-descriptor-file",
        () -> requiredEnvironment("MCP_JWT_SIGNING_KEY_DESCRIPTOR_FILE"));
  }

  @Test
  void private_TLS와_RS256으로_initialize_list_call을_종단_검증한다() {
    McpInvocationResult result =
        client.call(
            new McpInvocation(
                "search_jeju_places",
                "request-live-0001",
                Map.of("request", Map.of("query", "제주", "limit", 1)),
                "a".repeat(64),
                McpCallParent.forComputeRun(
                    UUID.fromString("10000000-0000-0000-0000-000000000001")),
                Map.of(),
                Map.of()));

    assertThat(client.isReady()).isTrue();
    assertThat(result.structuredContent())
        .containsKey("status")
        .doesNotContainKeys("raw", "geometry", "original_text");
  }

  private static String requiredEnvironment(String name) {
    return java.util.Objects.requireNonNull(System.getenv(name), name + " 환경값이 필요합니다.");
  }
}
