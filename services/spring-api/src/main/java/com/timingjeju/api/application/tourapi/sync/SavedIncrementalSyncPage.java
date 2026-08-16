package com.timingjeju.api.application.tourapi.sync;

import com.timingjeju.api.application.snapshot.SnapshotStatus;
import java.time.Instant;
import java.util.Objects;

public record SavedIncrementalSyncPage(
    IncrementalSyncSourceResponse storedResponse,
    int pageNo,
    String payloadHash,
    Instant fetchedAt,
    IncrementalSyncLineage lineage,
    boolean replayed,
    SnapshotStatus status) {
  public SavedIncrementalSyncPage {
    storedResponse = Objects.requireNonNull(storedResponse, "storedResponse는 필수입니다.");
    if (pageNo < 1 || payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("saved page identity가 올바르지 않습니다.");
    }
    fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt은 필수입니다.");
    lineage = Objects.requireNonNull(lineage, "lineage는 필수입니다.");
    status = Objects.requireNonNull(status, "status는 필수입니다.");
  }
}
