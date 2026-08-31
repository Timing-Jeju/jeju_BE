package com.timingjeju.api.global.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class McpContractGuardTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void schema_object의_key순서가_달라도_fingerprint가_같다() {
    Map<String, Object> first =
        Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")));
    Map<String, Object> second =
        Map.of("properties", Map.of("name", Map.of("type", "string")), "type", "object");

    assertThat(McpSchemaFingerprint.sha256(first, objectMapper))
        .isEqualTo(McpSchemaFingerprint.sha256(second, objectMapper));
  }

  @Test
  void 발견한_도구가_빠지거나_schema_checksum이_다르면_초기화를_닫힌_실패로_중단한다() {
    McpExpectedCatalog expected = McpExpectedCatalog.load(objectMapper);
    McpContractGuard guard = new McpContractGuard(objectMapper, expected);

    assertThatThrownBy(() -> guard.verifyCatalog(List.of()))
        .isInstanceOf(McpContractException.class)
        .hasMessageContaining("MCP_CONTRACT_INVALID");

    McpDiscoveredTool wrong =
        new McpDiscoveredTool(
            "recommend_jeju_day_trips", Map.of("type", "object"), Map.of("type", "object"));
    assertThatThrownBy(() -> guard.verifyCatalog(List.of(wrong)))
        .isInstanceOf(McpContractException.class)
        .hasMessageContaining("MCP_CONTRACT_INVALID");
  }

  @Test
  void structuredContent는_object와_output_schema와_ID_allowlist를_모두_통과해야_한다() {
    Map<String, Object> schema =
        Map.of(
            "type",
            "object",
            "additionalProperties",
            false,
            "required",
            List.of("place_id"),
            "properties",
            Map.of("place_id", Map.of("type", "string")));
    McpContractGuard guard = McpContractGuard.forSingleTool(objectMapper, "test_tool", schema);

    assertThatThrownBy(
            () -> guard.validateStructuredContent("test_tool", "not-an-object", Map.of()))
        .isInstanceOf(McpContractException.class)
        .hasMessageContaining("structuredContent");
    assertThatThrownBy(
            () ->
                guard.validateStructuredContent(
                    "test_tool",
                    Map.of("place_id", "tourapi.place:unknown"),
                    Map.of("place_id", Set.of("tourapi.place:allowed"))))
        .isInstanceOf(McpContractException.class)
        .hasMessageContaining("UNKNOWN_MCP_ID");
    assertThatThrownBy(
            () ->
                guard.validateStructuredContent(
                    "test_tool",
                    Map.of("place_id", "tourapi.place:allowed", "raw", "x"),
                    Map.of("place_id", Set.of("tourapi.place:allowed"))))
        .isInstanceOf(McpContractException.class)
        .hasMessageContaining("MCP_CONTRACT_INVALID");

    assertThat(
            guard.validateStructuredContent(
                "test_tool",
                Map.of("place_id", "tourapi.place:allowed"),
                Map.of("place_id", Set.of("tourapi.place:allowed"))))
        .isEqualTo(Map.of("place_id", "tourapi.place:allowed"));
  }
}
