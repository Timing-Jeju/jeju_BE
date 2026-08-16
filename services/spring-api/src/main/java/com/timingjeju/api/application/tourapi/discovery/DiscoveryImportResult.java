package com.timingjeju.api.application.tourapi.discovery;

import com.timingjeju.api.application.tourapi.place.PlaceRejectReason;
import java.util.Map;
import java.util.UUID;

public record DiscoveryImportResult(
    UUID runId,
    int pageCount,
    int inserted,
    int updated,
    int skipped,
    int rejected,
    Map<PlaceRejectReason, Integer> rejectedReasons,
    boolean replayed) {

  public DiscoveryImportResult {
    rejectedReasons = Map.copyOf(rejectedReasons);
  }

  public static DiscoveryImportResult replayed(UUID runId) {
    return new DiscoveryImportResult(runId, 0, 0, 0, 0, 0, Map.of(), true);
  }
}
