package com.timingjeju.api.application.asyncrun;

import java.util.Objects;

public final class RetryableRunException extends RuntimeException {

  private final String stableErrorCode;

  public RetryableRunException(String stableErrorCode) {
    super("비동기 run 실행을 재시도할 수 있습니다.");
    this.stableErrorCode = requireStableErrorCode(stableErrorCode);
  }

  public String stableErrorCode() {
    return stableErrorCode;
  }

  private static String requireStableErrorCode(String value) {
    Objects.requireNonNull(value, "stableErrorCode는 필수입니다.");
    if (value.isBlank() || value.length() > 100) {
      throw new IllegalArgumentException("stableErrorCode는 1~100자의 비공백 값이어야 합니다.");
    }
    return value;
  }
}
