package com.timingjeju.api.application.asyncrun;

import java.util.Objects;
import java.util.UUID;

public record RunLease(UUID runId, long fencingToken, int attempt) {

  public RunLease {
    Objects.requireNonNull(runId, "runId은 필수입니다.");
    if (fencingToken <= 0) {
      throw new IllegalArgumentException("fencingToken은 양수여야 합니다.");
    }
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt는 양수여야 합니다.");
    }
  }
}
