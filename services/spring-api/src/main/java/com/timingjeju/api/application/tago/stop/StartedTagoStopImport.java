package com.timingjeju.api.application.tago.stop;

import com.timingjeju.api.application.importing.ImportRunCounts;
import com.timingjeju.api.application.importing.ImportRunLease;
import java.util.Objects;

public record StartedTagoStopImport(
    ImportRunLease lease,
    boolean replayed,
    long checkpointVersion,
    String cityCode,
    ImportRunCounts counts) {
  public StartedTagoStopImport {
    lease = Objects.requireNonNull(lease, "lease는 필수입니다.");
    counts = Objects.requireNonNull(counts, "counts는 필수입니다.");
    if (checkpointVersion < 0) throw TagoStopImportException.invalidResponse();
  }
}
