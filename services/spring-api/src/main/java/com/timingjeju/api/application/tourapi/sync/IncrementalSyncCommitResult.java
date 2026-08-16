package com.timingjeju.api.application.tourapi.sync;

import com.timingjeju.api.application.importing.ImportRunCounts;
import java.util.Objects;

public record IncrementalSyncCommitResult(ImportRunCounts counts, long checkpointVersion) {
  public IncrementalSyncCommitResult {
    counts = Objects.requireNonNull(counts, "counts는 필수입니다.");
    if (checkpointVersion < 1) {
      throw new IllegalArgumentException("checkpointVersion은 양수여야 합니다.");
    }
  }
}
