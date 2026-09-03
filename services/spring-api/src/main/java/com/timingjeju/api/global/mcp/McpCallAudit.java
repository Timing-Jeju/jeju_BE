package com.timingjeju.api.global.mcp;

import java.util.Objects;
import java.util.Set;

public record McpCallAudit(
    McpCallParent parent,
    String requestId,
    String toolName,
    String contractVersion,
    String commandInputHash,
    String mcpInputHash,
    String schemaChecksum,
    int requestFactCount,
    int responseFactCount,
    int attemptNo,
    String status,
    int latencyMs,
    String errorCode) {
  private static final Set<String> STATUSES =
      Set.of(
          "succeeded",
          "domain_failure",
          "contract_invalid",
          "transport_error",
          "authentication_failed",
          "protocol_invalid");

  public McpCallAudit {
    Objects.requireNonNull(parent, "parent는 필수입니다.");
    requireText(requestId);
    if (!requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
      throw new IllegalArgumentException("MCP audit requestId가 올바르지 않습니다.");
    }
    requireText(toolName);
    requireText(contractVersion);
    requireHash(commandInputHash);
    requireHash(mcpInputHash);
    requireHash(schemaChecksum);
    if (requestFactCount < 0 || responseFactCount < 0 || latencyMs < 0) {
      throw new IllegalArgumentException("MCP audit count와 latency는 음수일 수 없습니다.");
    }
    if (attemptNo < 1 || attemptNo > 5) {
      throw new IllegalArgumentException("MCP audit attempt 범위가 올바르지 않습니다.");
    }
    if (!STATUSES.contains(status)) {
      throw new IllegalArgumentException("MCP audit status가 올바르지 않습니다.");
    }
    if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_]{0,99}")) {
      throw new IllegalArgumentException("MCP audit errorCode가 올바르지 않습니다.");
    }
    if (status.equals("succeeded") != (errorCode == null)) {
      throw new IllegalArgumentException("성공 여부와 errorCode가 일치하지 않습니다.");
    }
  }

  private static void requireHash(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("MCP audit hash가 올바르지 않습니다.");
    }
  }

  private static void requireText(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("MCP audit 값은 필수입니다.");
  }
}
