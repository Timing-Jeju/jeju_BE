package com.timingjeju.api.global.mcp;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record McpInvocation(
    String toolName,
    String requestId,
    Map<String, Object> arguments,
    String commandInputHash,
    McpCallParent parent,
    Map<String, Set<String>> outboundIdAllowlist,
    Map<String, Set<String>> inboundIdAllowlist) {

  public McpInvocation {
    Objects.requireNonNull(toolName, "toolName은 필수입니다.");
    if (requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("requestId 형식이 올바르지 않습니다.");
    }
    arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments는 필수입니다."));
    if (arguments.containsKey("requestId") || arguments.containsKey("inputHash")) {
      throw new IllegalArgumentException("wire metadata는 client가 생성해야 합니다.");
    }
    if (commandInputHash == null || !commandInputHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("commandInputHash는 SHA-256이어야 합니다.");
    }
    Objects.requireNonNull(parent, "MCP call parent는 필수입니다.");
    outboundIdAllowlist = immutableAllowlist(outboundIdAllowlist);
    inboundIdAllowlist = immutableAllowlist(inboundIdAllowlist);
  }

  private static Map<String, Set<String>> immutableAllowlist(Map<String, Set<String>> source) {
    Objects.requireNonNull(source, "ID allowlist는 필수입니다.");
    java.util.LinkedHashMap<String, Set<String>> copy = new java.util.LinkedHashMap<>();
    source.forEach((field, values) -> copy.put(field, Set.copyOf(values)));
    return Map.copyOf(copy);
  }
}
