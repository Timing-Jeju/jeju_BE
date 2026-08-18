package com.timingjeju.api.application.placestop;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record PlaceStopLinkBatch(
    Set<UUID> changedPlaceIds,
    Set<UUID> changedStopIds,
    String sourceProvider,
    Instant observedAt,
    String fingerprint,
    boolean complete) {

  public PlaceStopLinkBatch {
    changedPlaceIds =
        Set.copyOf(Objects.requireNonNull(changedPlaceIds, "changedPlaceIds는 필수입니다."));
    changedStopIds = Set.copyOf(Objects.requireNonNull(changedStopIds, "changedStopIds는 필수입니다."));
    if (changedPlaceIds.isEmpty() && changedStopIds.isEmpty()) {
      throw new IllegalArgumentException("변경된 place 또는 stop scope가 필요합니다.");
    }
    if (sourceProvider == null || sourceProvider.isBlank()) {
      throw new IllegalArgumentException("sourceProvider는 비어 있을 수 없습니다.");
    }
    observedAt = Objects.requireNonNull(observedAt, "observedAt은 필수입니다.");
    if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("fingerprint는 64자리 소문자 hex여야 합니다.");
    }
  }
}
