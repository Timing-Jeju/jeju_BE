package com.timingjeju.api.global.mcp;

public interface McpToolClient {
  McpInvocationResult call(McpInvocation invocation);

  boolean isReady();
}
