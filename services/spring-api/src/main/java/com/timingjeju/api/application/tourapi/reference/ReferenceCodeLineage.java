package com.timingjeju.api.application.tourapi.reference;

import java.util.Objects;
import java.util.UUID;

public record ReferenceCodeLineage(
    String operationKey, String requestFingerprint, UUID snapshotId, UUID importRunId) {
  public ReferenceCodeLineage {
    operationKey = required(operationKey);
    requestFingerprint = required(requestFingerprint);
    if (!requestFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestFingerprint 형식이 올바르지 않습니다.");
    }
    snapshotId = Objects.requireNonNull(snapshotId, "snapshotId는 필수입니다.");
    importRunId = Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
  }

  private static String required(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("lineage 값은 필수입니다.");
    }
    return value.strip();
  }
}
