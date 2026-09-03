package com.timingjeju.api.global.mcp;

import java.util.Objects;

record McpResilientResult<T>(T value, int attemptCount) {

  McpResilientResult {
    Objects.requireNonNull(value, "value는 필수입니다.");
    if (attemptCount < 1 || attemptCount > 5) {
      throw new IllegalArgumentException("attemptCount는 1 이상 5 이하여야 합니다.");
    }
  }
}
