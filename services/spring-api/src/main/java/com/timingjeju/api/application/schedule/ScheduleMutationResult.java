package com.timingjeju.api.application.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ScheduleMutationResult(
    UUID tripId,
    UUID previousScheduleVersionId,
    UUID activeScheduleVersionId,
    int versionNo,
    long tripRevision,
    List<UUID> changedItemIds,
    Instant updatedAt) {
  public ScheduleMutationResult {
    Objects.requireNonNull(tripId);
    Objects.requireNonNull(previousScheduleVersionId);
    Objects.requireNonNull(activeScheduleVersionId);
    changedItemIds = List.copyOf(changedItemIds);
    Objects.requireNonNull(updatedAt);
    if (versionNo < 1 || tripRevision < 1 || changedItemIds.isEmpty()) {
      throw new IllegalArgumentException("일정 변경 결과가 올바르지 않습니다.");
    }
  }
}
