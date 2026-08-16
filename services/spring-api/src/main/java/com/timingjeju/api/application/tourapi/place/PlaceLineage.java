package com.timingjeju.api.application.tourapi.place;

import java.util.Objects;
import java.util.UUID;

public record PlaceLineage(
    String operationKey, String requestFingerprint, UUID snapshotId, UUID importRunId) {
  public PlaceLineage {
    if (!("areaBasedList2".equals(operationKey)
        || "areaBasedSyncList2".equals(operationKey)
        || "locationBasedList2".equals(operationKey)
        || "searchKeyword2".equals(operationKey)
        || "searchStay2".equals(operationKey))) {
      throw new IllegalArgumentException("operationKey가 올바르지 않습니다.");
    }
    if (requestFingerprint == null || !requestFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("requestFingerprint 형식이 올바르지 않습니다.");
    }
    snapshotId = Objects.requireNonNull(snapshotId, "snapshotId는 필수입니다.");
    importRunId = Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
  }
}
