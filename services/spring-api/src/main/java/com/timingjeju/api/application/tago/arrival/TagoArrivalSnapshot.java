package com.timingjeju.api.application.tago.arrival;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record TagoArrivalSnapshot(
    List<TagoArrival> arrivals,
    Instant observedAt,
    Instant expiresAt,
    boolean stale,
    UUID importRunId,
    UUID sourceSnapshotId) {

  public TagoArrivalSnapshot {
    arrivals = List.copyOf(Objects.requireNonNull(arrivals, "arrivals는 필수입니다."));
    if (arrivals.isEmpty()) throw TagoArrivalException.emptyResult();
    Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
    Objects.requireNonNull(expiresAt, "expiresAt은 필수입니다.");
    if (expiresAt.isBefore(observedAt)) throw new IllegalArgumentException("expiresAt이 잘못되었습니다.");
    Objects.requireNonNull(importRunId, "importRunId는 필수입니다.");
    Objects.requireNonNull(sourceSnapshotId, "sourceSnapshotId는 필수입니다.");
  }

  public TagoArrivalSnapshot asStale() {
    if (stale) return this;
    return new TagoArrivalSnapshot(
        arrivals, observedAt, expiresAt, true, importRunId, sourceSnapshotId);
  }
}
