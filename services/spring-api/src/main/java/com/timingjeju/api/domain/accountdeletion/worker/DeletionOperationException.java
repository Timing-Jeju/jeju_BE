package com.timingjeju.api.domain.accountdeletion.worker;

import java.util.Objects;

public final class DeletionOperationException extends RuntimeException {

  public enum Disposition {
    RETRYABLE,
    TERMINAL
  }

  private final String stableFailureCode;
  private final Disposition disposition;

  private DeletionOperationException(
      String stableFailureCode, Disposition disposition, Throwable cause) {
    super(stableFailureCode, cause);
    if (stableFailureCode == null || stableFailureCode.isBlank()) {
      throw new IllegalArgumentException("stableFailureCode는 필수입니다.");
    }
    this.stableFailureCode = stableFailureCode;
    this.disposition = Objects.requireNonNull(disposition, "disposition은 필수입니다.");
  }

  public static DeletionOperationException retryable(String stableFailureCode) {
    return new DeletionOperationException(stableFailureCode, Disposition.RETRYABLE, null);
  }

  public static DeletionOperationException retryable(String stableFailureCode, Throwable cause) {
    return new DeletionOperationException(stableFailureCode, Disposition.RETRYABLE, cause);
  }

  public static DeletionOperationException terminal(String stableFailureCode) {
    return new DeletionOperationException(stableFailureCode, Disposition.TERMINAL, null);
  }

  public static DeletionOperationException terminal(String stableFailureCode, Throwable cause) {
    return new DeletionOperationException(stableFailureCode, Disposition.TERMINAL, cause);
  }

  public String stableFailureCode() {
    return stableFailureCode;
  }

  public boolean isRetryable() {
    return disposition == Disposition.RETRYABLE;
  }
}
