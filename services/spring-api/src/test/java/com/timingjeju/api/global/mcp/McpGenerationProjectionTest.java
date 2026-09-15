package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class McpGenerationProjectionTest {
  @Test
  void 기존_고정_ID_호출도_정상_null_필드를_유지한다() {
    var response = new java.util.LinkedHashMap<String, Object>();
    response.put("route_id", "generated-route");
    response.put("failure", null);
    var f = fixture(response);
    var original = invocation(true);
    var request =
        new McpInvocation(
            original.toolName(),
            original.requestId(),
            original.arguments(),
            original.commandInputHash(),
            original.parent(),
            original.outboundIdAllowlist(),
            Map.of("route_id", java.util.Set.of("generated-route")));
    assertThat(f.client().call(request).structuredContent()).containsEntry("failure", null);
  }

  @Test
  void 정상_생성응답의_null_failure는_보존하여_검증기에_전달한다() {
    var response = new java.util.LinkedHashMap<String, Object>();
    response.put("route_id", "generated-route");
    response.put("failure", null);
    var f = fixture(response);
    assertThat(
            f.client()
                .callGeneration(
                    invocation(true),
                    content -> {
                      assertThat(content).containsKey("failure");
                      assertThat(content.get("failure")).isNull();
                      return "normalized";
                    })
                .projection())
        .isEqualTo("normalized");
  }

  @Test
  void 생성된_ID는_스키마와_전용_검증을_거친_projection만_반환한다() {
    var f = fixture(Map.of("route_id", "generated-route"));
    var result =
        f.client()
            .callGeneration(
                invocation(true),
                content -> {
                  assertThat(content).containsEntry("route_id", "generated-route");
                  return "normalized-only";
                });
    assertThat(result.projection()).isEqualTo("normalized-only");
    assertThat(result.mcpInputHash()).matches("[0-9a-f]{64}");
    assertThat(result.attemptCount()).isEqualTo(1);
    verify(f.sdk()).callTool(any());
  }

  @Test
  void 스키마_실패는_projection을_호출하지_않는다() {
    var f = fixture(Map.of("route_id", 1));
    var called = new AtomicBoolean();
    assertThatThrownBy(
            () ->
                f.client()
                    .callGeneration(
                        invocation(true),
                        content -> {
                          called.set(true);
                          return "invalid";
                        }))
        .isInstanceOf(McpContractException.class)
        .hasMessage("MCP_CONTRACT_INVALID");
    assertThat(called).isFalse();
  }

  @Test
  void projection_검증_실패는_원문_없는_계약오류로_한번만_기록한다() {
    var f = fixture(Map.of("route_id", "generated-route"));
    assertThatThrownBy(
            () ->
                f.client()
                    .callGeneration(
                        invocation(true),
                        content -> {
                          throw new IllegalArgumentException("synthetic provider detail");
                        }))
        .isInstanceOf(McpContractException.class)
        .hasMessage("MCP_CONTRACT_INVALID")
        .hasNoCause();
    var audit = org.mockito.ArgumentCaptor.forClass(McpCallAudit.class);
    verify(f.audit()).record(audit.capture());
    assertThat(audit.getValue().status()).isEqualTo("contract_invalid");
    assertThat(audit.getValue().errorCode()).isEqualTo("MCP_CONTRACT_INVALID");
    verify(f.sdk()).callTool(any());
  }

  @Test
  void 생성부모나_검증기_없이는_SDK를_호출하지_않는다() {
    var f = fixture(Map.of("route_id", "generated-route"));
    assertThatThrownBy(() -> f.client().callGeneration(invocation(false), content -> "x"))
        .isInstanceOf(McpContractException.class);
    assertThatThrownBy(() -> f.client().callGeneration(invocation(true), null))
        .isInstanceOf(McpContractException.class);
    verify(f.sdk(), never()).callTool(any());
  }

  private McpInvocation invocation(boolean generation) {
    return new McpInvocation(
        "recommend_jeju_day_trips",
        "generation-79",
        Map.of(),
        "a".repeat(64),
        generation
            ? McpCallParent.forGenerationRun(UUID.randomUUID())
            : McpCallParent.forComputeRun(UUID.randomUUID()),
        Map.of(),
        Map.of());
  }

  private Fixture fixture(Map<String, Object> response) {
    var mapper = new ObjectMapper();
    Map<String, Object> input =
        Map.of(
            "type",
            "object",
            "properties",
            Map.of("requestId", Map.of("type", "string"), "inputHash", Map.of("type", "string")));
    Map<String, Object> output =
        Map.of(
            "type",
            "object",
            "required",
            List.of("route_id"),
            "properties",
            Map.of("route_id", Map.of("type", "string")));
    var sdk = mock(McpSyncClient.class);
    var audit = mock(McpCallAuditWriter.class);
    when(sdk.isInitialized()).thenReturn(true);
    when(sdk.listTools())
        .thenReturn(
            new McpSchema.ListToolsResult(
                List.of(
                    new McpSchema.Tool(
                        "recommend_jeju_day_trips", null, null, input, output, null, null)),
                null));
    when(sdk.callTool(any()))
        .thenReturn(new McpSchema.CallToolResult(List.of(), false, response, Map.of()));
    var client =
        new SpringAiJejuMcpClient(
            sdk,
            McpContractGuard.forSingleTool(mapper, "recommend_jeju_day_trips", input, output),
            mapper,
            new SimpleMeterRegistry(),
            McpCallResilience.defaults(),
            audit);
    client.verifyServerContract();
    return new Fixture(client, sdk, audit);
  }

  private record Fixture(
      SpringAiJejuMcpClient client, McpSyncClient sdk, McpCallAuditWriter audit) {}
}
