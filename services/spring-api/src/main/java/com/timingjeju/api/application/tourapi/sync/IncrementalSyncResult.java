package com.timingjeju.api.application.tourapi.sync;

import com.timingjeju.api.application.importing.ImportRunCounts;
import java.util.Objects;
import java.util.UUID;

public record IncrementalSyncResult(
    UUID runId, int pageCount, ImportRunCounts counts, long checkpointVersion, boolean replayed) {
  public IncrementalSyncResult {
    runId = Objects.requireNonNull(runId, "runId는 필수입니다.");
    if (pageCount < 0 || checkpointVersion < 0) {
      throw new IllegalArgumentException("result count가 올바르지 않습니다.");
    }
    counts = Objects.requireNonNull(counts, "counts는 필수입니다.");
  }

  public static IncrementalSyncResult replayed(
      UUID runId, int pageCount, ImportRunCounts counts, long checkpointVersion) {
    return new IncrementalSyncResult(runId, pageCount, counts, checkpointVersion, true);
  }
}
