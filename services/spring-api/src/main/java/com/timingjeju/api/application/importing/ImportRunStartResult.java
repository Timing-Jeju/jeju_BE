package com.timingjeju.api.application.importing;

import java.util.Objects;

public record ImportRunStartResult(
    ImportRunLease lease,
    boolean replayed,
    ImportRunExecutionStatus status,
    ImportRunCounts counts) {
  public ImportRunStartResult {
    Objects.requireNonNull(lease, "lease는 필수입니다.");
    Objects.requireNonNull(status, "status는 필수입니다.");
    Objects.requireNonNull(counts, "counts는 필수입니다.");
    if (!replayed && status != ImportRunExecutionStatus.RUNNING) {
      throw new IllegalArgumentException("신규 import run은 running 상태여야 합니다.");
    }
  }

  public ImportRunStartResult(ImportRunLease lease, boolean replayed) {
    this(lease, replayed, ImportRunExecutionStatus.RUNNING, ImportRunCounts.zero());
  }
}
