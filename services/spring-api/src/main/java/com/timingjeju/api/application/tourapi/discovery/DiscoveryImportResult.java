package com.timingjeju.api.application.tourapi.discovery;

import com.timingjeju.api.application.importing.ImportRunCounts;
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

  public static DiscoveryImportResult replayed(UUID runId, ImportRunCounts counts, int pageCount) {
    return new DiscoveryImportResult(
        runId,
        pageCount,
        counts.insertedCount(),
        counts.updatedCount(),
        counts.skippedCount(),
        counts.rejectedCount(),
        Map.of(),
        true);
  }
}
