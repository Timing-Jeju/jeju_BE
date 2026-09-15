package com.timingjeju.api.global.mcp;

import java.util.Map;

public record McpInvocationResult(
    Map<String, Object> structuredContent, String mcpInputHash, int attemptCount) {

  public McpInvocationResult {
    structuredContent =
        java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(structuredContent));
    if (mcpInputHash == null || !mcpInputHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("mcpInputHash는 SHA-256이어야 합니다.");
    }
    if (attemptCount < 1 || attemptCount > 5) {
      throw new IllegalArgumentException("attemptCount는 1 이상 5 이하여야 합니다.");
    }
  }
}
