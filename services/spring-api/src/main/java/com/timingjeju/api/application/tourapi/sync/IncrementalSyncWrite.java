package com.timingjeju.api.application.tourapi.sync;

import java.time.Instant;
import java.util.Objects;

public record IncrementalSyncWrite(
    PlaceSyncChange change, Instant observedAt, IncrementalSyncLineage lineage) {
  public IncrementalSyncWrite {
    change = Objects.requireNonNull(change, "change는 필수입니다.");
    observedAt = Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
    lineage = Objects.requireNonNull(lineage, "lineage는 필수입니다.");
  }
}
