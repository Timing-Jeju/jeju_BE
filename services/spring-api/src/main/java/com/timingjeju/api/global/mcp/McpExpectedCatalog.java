package com.timingjeju.api.global.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public record McpExpectedCatalog(String contractVersion, Map<String, ExpectedTool> tools) {
  private static final String RESOURCE = "/mcp/mcp-tools-v0.7.json";

  public McpExpectedCatalog {
    Objects.requireNonNull(contractVersion, "contractVersion은 필수입니다.");
    tools = Map.copyOf(Objects.requireNonNull(tools, "tools는 필수입니다."));
  }

  public static McpExpectedCatalog load(ObjectMapper objectMapper) {
    Objects.requireNonNull(objectMapper, "objectMapper는 필수입니다.");
    try (InputStream stream = McpExpectedCatalog.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) throw new IllegalStateException("MCP manifest를 찾을 수 없습니다.");
      JsonNode root = objectMapper.readTree(stream);
      Map<String, ExpectedTool> tools = new LinkedHashMap<>();
      for (JsonNode tool : root.path("tools")) {
        ExpectedTool expected =
            new ExpectedTool(
                tool.path("name").asText(),
                tool.path("inputSchemaSha256").asText(),
                tool.path("outputSchemaSha256").asText());
        if (tools.putIfAbsent(expected.name(), expected) != null) {
          throw new IllegalStateException("MCP manifest에 중복 도구가 있습니다.");
        }
      }
      if (root.path("toolCount").intValue() != tools.size()) {
        throw new IllegalStateException("MCP manifest toolCount가 일치하지 않습니다.");
      }
      return new McpExpectedCatalog(root.path("contractVersion").asText(), tools);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("MCP manifest를 읽을 수 없습니다.", exception);
    }
  }

  public record ExpectedTool(String name, String inputSchemaSha256, String outputSchemaSha256) {
    public ExpectedTool {
      requireSha256(inputSchemaSha256);
      requireSha256(outputSchemaSha256);
    }

    private static void requireSha256(String value) {
      if (value == null || !value.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("schema checksum은 SHA-256이어야 합니다.");
      }
    }
  }
}
