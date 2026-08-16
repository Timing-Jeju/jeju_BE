package com.timingjeju.api.application.kma;

import java.util.Objects;
import java.util.UUID;

public record KmaWeatherLineage(
    String operationKey, String requestFingerprint, UUID snapshotId, UUID importRunId) {
  public KmaWeatherLineage {
    Objects.requireNonNull(operationKey, "operationKey는 필수입니다.");
    Objects.requireNonNull(requestFingerprint, "requestFingerprint는 필수입니다.");
    Objects.requireNonNull(snapshotId, "snapshotId는 필수입니다.");
    Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
  }
}
