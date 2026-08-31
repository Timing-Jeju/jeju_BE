package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class SpringAiJejuMcpClientTest {

  @Test
  void initialize_tools_list_tools_call과_structuredContent_검증을_공식_SDK로_수행한다() {
    ObjectMapper objectMapper = new ObjectMapper();
    Map<String, Object> inputSchema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("place_id"),
            "properties",
            Map.of("place_id", Map.of("type", "string")));
    Map<String, Object> outputSchema = inputSchema;
    McpContractGuard guard =
        McpContractGuard.forSingleTool(objectMapper, "test_tool", inputSchema, outputSchema);
    McpSyncClient sdkClient = mock(McpSyncClient.class);
    when(sdkClient.isInitialized()).thenReturn(true);
    when(sdkClient.listTools())
        .thenReturn(
            new McpSchema.ListToolsResult(
                List.of(
                    new McpSchema.Tool(
                        "test_tool", null, null, inputSchema, outputSchema, null, null)),
                null));
    when(sdkClient.callTool(any()))
        .thenReturn(
            new McpSchema.CallToolResult(
                List.of(), false, Map.of("place_id", "tourapi.place:1"), Map.of()));
    SpringAiJejuMcpClient client =
        new SpringAiJejuMcpClient(
            sdkClient,
            guard,
            objectMapper,
            new SimpleMeterRegistry(),
            McpCallResilience.defaults());
    client.verifyServerContract();

    McpInvocationResult result =
        client.call(
            new McpInvocation(
                "test_tool",
                Map.of("place_id", "tourapi.place:1"),
                "a".repeat(64),
                Map.of("place_id", Set.of("tourapi.place:1")),
                Map.of("place_id", Set.of("tourapi.place:1"))));

    assertThat(client.isReady()).isTrue();
    assertThat(result.structuredContent()).isEqualTo(Map.of("place_id", "tourapi.place:1"));
    assertThat(result.mcpInputHash()).matches("[0-9a-f]{64}").isNotEqualTo("a".repeat(64));
    assertThat(result.attemptCount()).isEqualTo(1);
    ArgumentCaptor<McpSchema.CallToolRequest> request =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(sdkClient).callTool(request.capture());
    assertThat(request.getValue().name()).isEqualTo("test_tool");
    assertThat(request.getValue().arguments()).isEqualTo(Map.of("place_id", "tourapi.place:1"));
  }
}
