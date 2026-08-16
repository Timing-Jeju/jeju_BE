package com.timingjeju.api.application.tago.arrival;

import com.timingjeju.api.application.snapshot.SnapshotStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SavedTagoArrivalSnapshot(
    TagoArrivalSourceResponse storedResponse,
    UUID snapshotId,
    String payloadHash,
    Instant observedAt,
    Instant expiresAt,
    boolean replayed,
    SnapshotStatus status) {

  public SavedTagoArrivalSnapshot {
    Objects.requireNonNull(storedResponse, "storedResponse는 필수입니다.");
    Objects.requireNonNull(snapshotId, "snapshotId는 필수입니다.");
    if (payloadHash == null || !payloadHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("payloadHash가 올바르지 않습니다.");
    }
    Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
    Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
    Objects.requireNonNull(status, "status는 필수입니다.");
  }
}
