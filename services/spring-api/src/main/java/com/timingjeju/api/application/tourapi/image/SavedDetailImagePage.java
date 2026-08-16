package com.timingjeju.api.application.tourapi.image;

import com.timingjeju.api.application.snapshot.SnapshotStatus;
import com.timingjeju.api.application.tourapi.detail.DetailSourceResponse;
import java.time.Instant;
import java.util.Objects;

public record SavedDetailImagePage(
    DetailSourceResponse storedResponse,
    int pageNo,
    String payloadHash,
    Instant fetchedAt,
    PlaceImageLineage lineage,
    boolean replayed,
    SnapshotStatus status) {
  public SavedDetailImagePage {
    storedResponse = Objects.requireNonNull(storedResponse, "storedResponse는 필수입니다.");
    if (pageNo < 1 || payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("saved image page identity가 올바르지 않습니다.");
    }
    fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt은 필수입니다.");
    lineage = Objects.requireNonNull(lineage, "lineage는 필수입니다.");
    status = Objects.requireNonNull(status, "status는 필수입니다.");
  }
}
