package com.timingjeju.api.global.mcp;

public final class McpRemoteCallException extends RuntimeException {
  private final String stableCode;
  private final boolean retryable;

  public McpRemoteCallException(String stableCode) {
    this(stableCode, false);
  }

  public McpRemoteCallException(String stableCode, boolean retryable) {
    super(stableCode);
    this.stableCode = stableCode;
    this.retryable = retryable;
  }

  public String stableCode() {
    return stableCode;
  }

  public boolean retryable() {
    return retryable;
  }
}
