package com.timingjeju.api.domain.accountdeletion.worker;

import java.util.Objects;
import java.util.UUID;

public record DeletionLease(UUID requestId, String owner, long fencingToken, int attempt) {

  public DeletionLease {
    Objects.requireNonNull(requestId, "requestId는 필수입니다.");
    if (owner == null || owner.isBlank() || owner.length() > 100) {
      throw new IllegalArgumentException("owner는 1~100자의 비공백 값이어야 합니다.");
    }
    if (fencingToken <= 0) {
      throw new IllegalArgumentException("fencingToken은 양수여야 합니다.");
    }
    if (attempt <= 0) {
      throw new IllegalArgumentException("attempt는 양수여야 합니다.");
    }
  }
}
