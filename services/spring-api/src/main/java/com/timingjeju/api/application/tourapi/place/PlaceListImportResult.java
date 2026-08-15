package com.timingjeju.api.application.tourapi.place;

import java.util.Map;
import java.util.UUID;

public record PlaceListImportResult(
    UUID runId,
    int pageCount,
    int inserted,
    int updated,
    int skipped,
    int rejected,
    Map<PlaceRejectReason, Integer> rejectedReasons,
    boolean replayed) {

  public PlaceListImportResult {
    rejectedReasons = Map.copyOf(rejectedReasons);
  }

  public static PlaceListImportResult replayed(UUID runId) {
    return new PlaceListImportResult(runId, 0, 0, 0, 0, 0, Map.of(), true);
  }
}
