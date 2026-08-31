package com.timingjeju.api.global.mcp;

public final class McpContractException extends RuntimeException {

  public McpContractException(String stableCode) {
    super(stableCode);
  }

  public McpContractException(String stableCode, Throwable cause) {
    super(stableCode, cause);
  }
}
