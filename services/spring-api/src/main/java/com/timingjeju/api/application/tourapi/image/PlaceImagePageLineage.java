package com.timingjeju.api.application.tourapi.image;

import java.time.Instant;
import java.util.Objects;

public record PlaceImagePageLineage(
    int pageNo,
    int rawItemCount,
    String payloadHash,
    Instant fetchedAt,
    PlaceImageLineage lineage) {
  public PlaceImagePageLineage {
    if (pageNo < 1
        || rawItemCount < 0
        || payloadHash == null
        || !payloadHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("image page lineage가 올바르지 않습니다.");
    }
    fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt은 필수입니다.");
    lineage = Objects.requireNonNull(lineage, "lineage는 필수입니다.");
  }
}
