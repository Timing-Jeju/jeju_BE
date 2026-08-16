package com.timingjeju.api.application.tourapi.image;

import java.util.Objects;
import java.util.UUID;

public record PlaceImageLineage(
    String operationKey, String requestFingerprint, UUID snapshotId, UUID importRunId) {
  public PlaceImageLineage {
    if (!"detailImage2".equals(operationKey)
        || requestFingerprint == null
        || !requestFingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("이미지 lineage가 올바르지 않습니다.");
    }
    snapshotId = Objects.requireNonNull(snapshotId, "snapshotId는 필수입니다.");
    importRunId = Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
  }
}
