package com.timingjeju.api.global.mcp;

@FunctionalInterface
public interface McpSigningKeyProvider {
  McpSigningKey current();
}
