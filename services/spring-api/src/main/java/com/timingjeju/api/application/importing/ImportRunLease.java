package com.timingjeju.api.application.importing;

import java.util.Objects;
import java.util.UUID;

public record ImportRunLease(UUID runId, UUID ownerToken, long fencingToken) {
  public ImportRunLease {
    Objects.requireNonNull(runId, "runId는 필수입니다.");
    Objects.requireNonNull(ownerToken, "ownerToken은 필수입니다.");
    if (fencingToken <= 0) {
      throw new IllegalArgumentException("fencingToken은 양수여야 합니다.");
    }
  }

  @Override
  public String toString() {
    return "ImportRunLease[runId="
        + runId
        + ", ownerToken=<redacted>, fencingToken="
        + fencingToken
        + "]";
  }
}
