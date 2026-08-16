package com.timingjeju.api.application.tourapi.sync;

import java.time.Instant;
import java.util.Objects;

public record IncrementalSyncPageLineage(
    int pageNo,
    int rawItemCount,
    String payloadHash,
    Instant fetchedAt,
    IncrementalSyncLineage lineage) {
  public IncrementalSyncPageLineage {
    if (pageNo < 1 || rawItemCount < 0) {
      throw new IllegalArgumentException("page lineage count가 올바르지 않습니다.");
    }
    if (payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("payloadHash 형식이 올바르지 않습니다.");
    }
    fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt은 필수입니다.");
    lineage = Objects.requireNonNull(lineage, "lineage는 필수입니다.");
  }
}
