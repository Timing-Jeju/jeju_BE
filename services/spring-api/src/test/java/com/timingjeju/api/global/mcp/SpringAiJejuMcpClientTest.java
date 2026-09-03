package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
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
            List.of("requestId", "inputHash", "place_id"),
            "properties",
            Map.of(
                "requestId", Map.of("type", "string"),
                "inputHash", Map.of("type", "string", "pattern", "^[0-9a-f]{64}$"),
                "place_id", Map.of("type", "string")));
    Map<String, Object> outputSchema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("place_id"),
            "properties",
            Map.of("place_id", Map.of("type", "string")));
    McpContractGuard guard =
        McpContractGuard.forSingleTool(objectMapper, "test_tool", inputSchema, outputSchema);
    McpSyncClient sdkClient = mock(McpSyncClient.class);
    McpCallAuditWriter auditWriter = mock(McpCallAuditWriter.class);
    when(sdkClient.isInitialized()).thenReturn(true);
    when(sdkClient.listTools())
        .thenReturn(
            new McpSchema.ListToolsResult(
                List.of(
                    new McpSchema.Tool(
                        "test_tool", null, null, inputSchema, outputSchema, null, null)),
                null));
    when(sdkClient.callTool(any()))
        .thenThrow(new RuntimeException(new TimeoutException("raw timeout detail")))
        .thenReturn(
            new McpSchema.CallToolResult(
                List.of(), false, Map.of("place_id", "tourapi.place:1"), Map.of()));
    SpringAiJejuMcpClient client =
        new SpringAiJejuMcpClient(
            sdkClient,
            guard,
            objectMapper,
            new SimpleMeterRegistry(),
            McpCallResilience.defaults(),
            auditWriter);
    client.verifyServerContract();

    McpInvocationResult result =
        client.call(
            new McpInvocation(
                "test_tool",
                "request-0001",
                Map.of("place_id", "tourapi.place:1"),
                "a".repeat(64),
                McpCallParent.forComputeRun(
                    UUID.fromString("10000000-0000-0000-0000-000000000001")),
                Map.of("place_id", Set.of("tourapi.place:1")),
                Map.of("place_id", Set.of("tourapi.place:1"))));

    assertThat(client.isReady()).isTrue();
    assertThat(result.structuredContent()).isEqualTo(Map.of("place_id", "tourapi.place:1"));
    assertThat(result.mcpInputHash()).matches("[0-9a-f]{64}").isNotEqualTo("a".repeat(64));
    assertThat(result.attemptCount()).isEqualTo(2);
    ArgumentCaptor<McpSchema.CallToolRequest> request =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(sdkClient, times(2)).callTool(request.capture());
    assertThat(request.getAllValues().getFirst().name()).isEqualTo("test_tool");
    assertThat(request.getAllValues().getFirst().arguments())
        .containsEntry("requestId", "request-0001")
        .containsEntry("place_id", "tourapi.place:1")
        .containsEntry("inputHash", result.mcpInputHash())
        .doesNotContainKey("commandInputHash");
    ArgumentCaptor<McpCallAudit> audit = ArgumentCaptor.forClass(McpCallAudit.class);
    verify(auditWriter, times(2)).record(audit.capture());
    assertThat(audit.getAllValues()).extracting(McpCallAudit::attemptNo).containsExactly(1, 2);
    assertThat(audit.getAllValues())
        .extracting(McpCallAudit::status)
        .containsExactly("transport_error", "succeeded");
    assertThat(audit.getAllValues().getFirst().errorCode()).isEqualTo("MCP_TIMEOUT");
    assertThat(audit.getAllValues().getLast().requestId()).isEqualTo("request-0001");
    assertThat(audit.getAllValues().getLast().commandInputHash()).isEqualTo("a".repeat(64));
    assertThat(audit.getAllValues().getLast().mcpInputHash()).isEqualTo(result.mcpInputHash());
  }

  @Test
  void 인증_protocol_실패_audit_writer가_실패해도_MCP를_재호출하지_않고_원래_code를_유지한다() {
    ObjectMapper objectMapper = new ObjectMapper();
    Map<String, Object> inputSchema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("requestId", "inputHash"),
            "properties",
            Map.of(
                "requestId", Map.of("type", "string"),
                "inputHash", Map.of("type", "string", "pattern", "^[0-9a-f]{64}$")));
    Map<String, Object> outputSchema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("status"),
            "properties",
            Map.of("status", Map.of("type", "string")));
    McpContractGuard guard =
        McpContractGuard.forSingleTool(objectMapper, "test_tool", inputSchema, outputSchema);
    McpSyncClient sdkClient = mock(McpSyncClient.class);
    McpCallAuditWriter auditWriter = mock(McpCallAuditWriter.class);
    when(sdkClient.isInitialized()).thenReturn(true);
    when(sdkClient.listTools())
        .thenReturn(
            new McpSchema.ListToolsResult(
                List.of(
                    new McpSchema.Tool(
                        "test_tool", null, null, inputSchema, outputSchema, null, null)),
                null));
    WebClientResponseException unauthorized =
        WebClientResponseException.create(
            401,
            "raw unauthorized",
            HttpHeaders.EMPTY,
            "raw provider body".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8);
    when(sdkClient.callTool(any()))
        .thenThrow(new McpTransportException("raw endpoint", unauthorized));
    doThrow(new IllegalStateException("raw audit database detail")).when(auditWriter).record(any());
    SpringAiJejuMcpClient client =
        new SpringAiJejuMcpClient(
            sdkClient,
            guard,
            objectMapper,
            new SimpleMeterRegistry(),
            McpCallResilience.defaults(),
            auditWriter);
    client.verifyServerContract();

    assertThatThrownBy(
            () ->
                client.call(
                    new McpInvocation(
                        "test_tool",
                        "request-auth-failure-0001",
                        Map.of(),
                        "a".repeat(64),
                        McpCallParent.forComputeRun(
                            UUID.fromString("10000000-0000-0000-0000-000000000001")),
                        Map.of(),
                        Map.of())))
        .isInstanceOfSatisfying(
            McpRemoteCallException.class,
            exception -> {
              assertThat(exception.stableCode()).isEqualTo("MCP_AUTHENTICATION_FAILED");
              assertThat(exception.retryable()).isFalse();
              assertThat(exception).hasNoCause();
            });
    verify(sdkClient).callTool(any());

    doThrow(
            new McpError(
                new McpSchema.JSONRPCResponse.JSONRPCError(-32700, "raw malformed response")))
        .when(sdkClient)
        .callTool(any());
    assertThatThrownBy(
            () ->
                client.call(
                    new McpInvocation(
                        "test_tool",
                        "request-protocol-failure-0001",
                        Map.of(),
                        "b".repeat(64),
                        McpCallParent.forComputeRun(
                            UUID.fromString("10000000-0000-0000-0000-000000000002")),
                        Map.of(),
                        Map.of())))
        .isInstanceOfSatisfying(
            McpRemoteCallException.class,
            exception -> {
              assertThat(exception.stableCode()).isEqualTo("MCP_PROTOCOL_INVALID");
              assertThat(exception.retryable()).isFalse();
              assertThat(exception).hasNoCause();
            });
    verify(sdkClient, times(2)).callTool(any());
  }
}
