package com.timingjeju.api.global.mcp;

import java.util.UUID;

public record McpCallParent(UUID computeRunId, UUID generationRunId, UUID scheduleRevisionRunId) {

  public McpCallParent {
    int count =
        (computeRunId == null ? 0 : 1)
            + (generationRunId == null ? 0 : 1)
            + (scheduleRevisionRunId == null ? 0 : 1);
    if (count != 1) throw new IllegalArgumentException("MCP call parent는 정확히 하나여야 합니다.");
  }

  public static McpCallParent forComputeRun(UUID id) {
    return new McpCallParent(id, null, null);
  }

  public static McpCallParent forGenerationRun(UUID id) {
    return new McpCallParent(null, id, null);
  }

  public static McpCallParent forScheduleRevisionRun(UUID id) {
    return new McpCallParent(null, null, id);
  }
}
