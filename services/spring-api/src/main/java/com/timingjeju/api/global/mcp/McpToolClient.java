package com.timingjeju.api.global.mcp;

public interface McpToolClient {
  McpInvocationResult call(McpInvocation invocation);

  <T> McpProjectedResult<T> callGeneration(
      McpInvocation invocation, McpGenerationProjector<T> projector);

  boolean isReady();
}
