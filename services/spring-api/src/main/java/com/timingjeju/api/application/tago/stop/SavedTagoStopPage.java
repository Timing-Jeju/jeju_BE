package com.timingjeju.api.application.tago.stop;

import com.timingjeju.api.application.snapshot.SnapshotStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SavedTagoStopPage(
    TagoStopSourceResponse storedResponse,
    int pageNo,
    UUID snapshotId,
    String payloadHash,
    Instant fetchedAt,
    boolean replayed,
    SnapshotStatus status) {
  public SavedTagoStopPage {
    storedResponse = Objects.requireNonNull(storedResponse, "storedResponse는 필수입니다.");
    snapshotId = Objects.requireNonNull(snapshotId, "snapshotId는 필수입니다.");
    payloadHash = Objects.requireNonNull(payloadHash, "payloadHash는 필수입니다.");
    fetchedAt = Objects.requireNonNull(fetchedAt, "fetchedAt은 필수입니다.");
    status = Objects.requireNonNull(status, "status는 필수입니다.");
  }
}
