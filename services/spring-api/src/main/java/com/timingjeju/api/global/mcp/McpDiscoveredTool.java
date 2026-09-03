package com.timingjeju.api.global.mcp;

import java.util.Map;
import java.util.Objects;

public record McpDiscoveredTool(
    String name, Map<String, Object> inputSchema, Map<String, Object> outputSchema) {

  public McpDiscoveredTool {
    Objects.requireNonNull(name, "name은 필수입니다.");
    inputSchema = Map.copyOf(Objects.requireNonNull(inputSchema, "inputSchema는 필수입니다."));
    outputSchema = Map.copyOf(Objects.requireNonNull(outputSchema, "outputSchema는 필수입니다."));
  }
}
