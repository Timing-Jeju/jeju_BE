package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// 전송 전 차단 검증이다. 공개 MCP 0.8 계약의 전체 전환 증거를 대신하지 않는다.
@Tag("unit")
class McpWireNoLocationPreHashTest {
  private static final String TOOL = "revalidate_jeju_day_trip";
  private static final String EVALUATE_TOOL = "evaluate_jeju_day_trip";
  private final ObjectMapper mapper = new ObjectMapper();
  private final McpSyncClient sdk = mock(McpSyncClient.class);
  private final McpContractGuard guard = mock(McpContractGuard.class);
  private final McpCallAuditWriter audit = mock(McpCallAuditWriter.class);

  private SpringAiJejuMcpClient client() {
    when(sdk.isInitialized()).thenReturn(true);
    when(sdk.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));
    when(guard.contractVersion()).thenReturn("0.7.0");
    when(guard.schemaChecksum(TOOL)).thenReturn("b".repeat(64));
    when(guard.schemaChecksum(EVALUATE_TOOL)).thenReturn("b".repeat(64));
    var client =
        new SpringAiJejuMcpClient(
            sdk, guard, mapper, new SimpleMeterRegistry(), McpCallResilience.defaults(), audit);
    client.verifyServerContract();
    clearInvocations(sdk);
    return client;
  }

  private Map<String, Object> historicalRequest() throws java.io.IOException {
    Map<String, Object> request;
    try (var resource =
        getClass().getResourceAsStream("/mcp/revalidate-synthetic-v07.input.json")) {
      request = mapper.readValue(resource, new TypeReference<Map<String, Object>>() {});
    }
    return request;
  }

  // 원본 0.7 fixture는 역사 자료다. 좌표를 빼도 current_* 출처가 검증되지는 않는다.
  private McpInvocation invocation(boolean withPosition) throws java.io.IOException {
    var request = historicalRequest();
    request.remove("current_position");
    if (withPosition) request.put("current_position", Map.of("latitude", 0, "longitude", 0));
    return new McpInvocation(
        TOOL,
        "request-0001",
        Map.of("request", request),
        "a".repeat(64),
        McpCallParent.forComputeRun(UUID.fromString("44000000-0000-0000-0000-000000000001")),
        Map.of(),
        Map.of());
  }

  private McpInvocation plannedEvaluation() throws java.io.IOException {
    return new McpInvocation(
        EVALUATE_TOOL,
        "request-0001",
        Map.of("request", historicalRequest().get("itinerary")),
        "a".repeat(64),
        McpCallParent.forComputeRun(UUID.fromString("44000000-0000-0000-0000-000000000001")),
        Map.of(),
        Map.of());
  }

  @Test
  void wire_계약_거부는_hash_계산과_외부호출_audit_전에_발생한다() throws java.io.IOException {
    when(guard.validateArguments(any(), any(), any()))
        .thenThrow(new McpContractException("MCP_CONTRACT_MISMATCH"));
    var client = client();
    var invocation = plannedEvaluation();
    try (var hashes = mockStatic(McpSchemaFingerprint.class, CALLS_REAL_METHODS)) {
      assertThatThrownBy(() -> client.call(invocation)).isInstanceOf(McpContractException.class);
      hashes.verifyNoInteractions();
    }
    verifyNoInteractions(sdk, audit);
    verify(guard).validateArguments(any(), any(), any());
  }

  @Test
  void 과거_schema가_허용해도_현재위치_wire는_hash와_외부호출_전에_거부한다() throws java.io.IOException {
    when(guard.validateArguments(any(), any(), any())).thenAnswer(call -> call.getArgument(1));
    var client = client();
    var invocation = invocation(true);
    try (var hashes = mockStatic(McpSchemaFingerprint.class, CALLS_REAL_METHODS)) {
      assertThatThrownBy(() -> client.call(invocation)).isInstanceOf(McpContractException.class);
      hashes.verifyNoInteractions();
    }
    verifyNoInteractions(sdk, audit);
  }

  @Test
  void 합성_계획_평가는_현재진행_위치없이_wire_hash와_호출까지_도달한다() throws java.io.IOException {
    when(guard.validateArguments(any(), any(), any())).thenAnswer(call -> call.getArgument(1));
    when(sdk.callTool(any())).thenThrow(new McpRemoteCallException("MCP_TOOL_ERROR", false));
    var client = client();
    var invocation = plannedEvaluation();
    try (var hashes = mockStatic(McpSchemaFingerprint.class, CALLS_REAL_METHODS)) {
      assertThatThrownBy(() -> client.call(invocation)).isInstanceOf(McpRemoteCallException.class);
      hashes.verify(() -> McpSchemaFingerprint.sha256(any(), any()));
    }
    verify(sdk).callTool(any());
    verify(guard, times(2)).validateArguments(any(), any(), any());
    var sent = org.mockito.ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(sdk).callTool(sent.capture());
    var hashDocument = new LinkedHashMap<>(sent.getValue().arguments());
    var actualHash = hashDocument.remove("inputHash");
    assertThat(actualHash).isEqualTo(McpSchemaFingerprint.sha256(hashDocument, mapper));
    assertThat(actualHash).isNotEqualTo("0".repeat(64));
  }

  @Test
  void schema_검증_경계에서_위치가_추가되어도_최종_snapshot을_hash_전에_거부한다() throws java.io.IOException {
    var planned = plannedEvaluation();
    when(guard.validateArguments(any(), any(), any()))
        .thenAnswer(
            call -> {
              Map<String, Object> supplied = call.getArgument(1);
              var changed =
                  mapper.convertValue(
                      supplied, new TypeReference<LinkedHashMap<String, Object>>() {});
              @SuppressWarnings("unchecked")
              var request = (Map<String, Object>) changed.get("request");
              request.put("current_position", Map.of("latitude", 0, "longitude", 0));
              return changed;
            });
    var client = client();
    try (var hashes = mockStatic(McpSchemaFingerprint.class, CALLS_REAL_METHODS)) {
      assertThatThrownBy(() -> client.call(planned)).isInstanceOf(McpContractException.class);
      hashes.verifyNoInteractions();
    }
    verifyNoInteractions(sdk, audit);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "current_position",
        "current_place_id",
        "current_stop_id",
        "current_route_id",
        "nearest_place_id",
        "nearest_stop_id",
        "current_region_code",
        "nearest_region",
        "coarse_location",
        "locationDigest",
        "geo_hash"
      })
  void 정상_계획_평가에도_위치필드만_추가하면_hash와_외부호출_전에_거부한다(String key) throws java.io.IOException {
    when(guard.validateArguments(any(), any(), any())).thenAnswer(call -> call.getArgument(1));
    var client = client();
    var planned = plannedEvaluation();
    var request =
        mapper.convertValue(
            planned.arguments().get("request"),
            new TypeReference<LinkedHashMap<String, Object>>() {});
    request.put(
        key,
        key.equals("current_position")
            ? Map.of("latitude", 0, "longitude", 0)
            : "synthetic-derived-id");
    var invocation =
        new McpInvocation(
            EVALUATE_TOOL,
            "request-0001",
            Map.of("request", request),
            "a".repeat(64),
            McpCallParent.forComputeRun(UUID.fromString("44000000-0000-0000-0000-000000000001")),
            Map.of(),
            Map.of());
    try (var hashes = mockStatic(McpSchemaFingerprint.class, CALLS_REAL_METHODS)) {
      assertThatThrownBy(() -> client.call(invocation)).isInstanceOf(McpContractException.class);
      hashes.verifyNoInteractions();
    }
    verifyNoInteractions(sdk, audit);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "current_place_id",
        "current_stop_id",
        "current_route_id",
        "nearest_place_id",
        "nearest_stop_id"
      })
  void 출처불명_현재_또는_인근_ID만_있어도_hash와_외부호출_전에_거부한다(String key) throws java.io.IOException {
    when(guard.validateArguments(any(), any(), any())).thenAnswer(call -> call.getArgument(1));
    var client = client();
    var request = historicalRequest();
    request.remove("current_position");
    request.put("progress", Map.of(key, "synthetic-derived-id"));
    var invocation =
        new McpInvocation(
            TOOL,
            "request-0001",
            Map.of("request", request),
            "a".repeat(64),
            McpCallParent.forComputeRun(UUID.fromString("44000000-0000-0000-0000-000000000001")),
            Map.of(),
            Map.of());
    try (var hashes = mockStatic(McpSchemaFingerprint.class, CALLS_REAL_METHODS)) {
      assertThatThrownBy(() -> client.call(invocation)).isInstanceOf(McpContractException.class);
      hashes.verifyNoInteractions();
    }
    verifyNoInteractions(sdk, audit);
  }
}
