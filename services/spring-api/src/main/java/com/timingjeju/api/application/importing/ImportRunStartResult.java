package com.timingjeju.api.application.importing;

import java.util.Objects;

public record ImportRunStartResult(ImportRunLease lease, boolean replayed) {
  public ImportRunStartResult {
    Objects.requireNonNull(lease, "lease는 필수입니다.");
  }
}
