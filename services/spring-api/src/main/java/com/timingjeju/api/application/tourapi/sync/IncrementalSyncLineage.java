package com.timingjeju.api.application.tourapi.sync;

import java.util.Objects;
import java.util.UUID;

public record IncrementalSyncLineage(
    String operationKey, String requestFingerprint, UUID snapshotId, UUID importRunId) {
  public IncrementalSyncLineage {
    if (!"areaBasedSyncList2".equals(operationKey)) {
      throw new IllegalArgumentException("operationKey가 올바르지 않습니다.");
    }
    if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestFingerprint 형식이 올바르지 않습니다.");
    }
    snapshotId = Objects.requireNonNull(snapshotId, "snapshotId는 필수입니다.");
    importRunId = Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
  }
}
