package com.timingjeju.api.application.tourapi.detail;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record DetailLineage(
    String operationKey, String requestFingerprint, UUID snapshotId, UUID importRunId) {
  private static final Set<String> OPERATIONS = Set.of("detailCommon2", "detailIntro2");

  public DetailLineage {
    if (!OPERATIONS.contains(operationKey)) {
      throw new IllegalArgumentException("operationKey가 올바르지 않습니다.");
    }
    if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestFingerprint 형식이 올바르지 않습니다.");
    }
    snapshotId = Objects.requireNonNull(snapshotId, "snapshotId는 필수입니다.");
    importRunId = Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
  }
}
