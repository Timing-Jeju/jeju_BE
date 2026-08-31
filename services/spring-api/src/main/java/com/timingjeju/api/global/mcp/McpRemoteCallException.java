package com.timingjeju.api.global.mcp;

public final class McpRemoteCallException extends RuntimeException {

  public McpRemoteCallException(String stableCode) {
    super(stableCode);
  }

  public McpRemoteCallException(String stableCode, Throwable cause) {
    super(stableCode, cause);
  }
}
