package com.timingjeju.api.global.mcp;

public interface JejuMcpClient {
  McpInvocationResult call(McpInvocation invocation);

  boolean isReady();
}
