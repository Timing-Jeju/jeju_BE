package com.timingjeju.api.application.tourapi.sync;

import java.time.Instant;
import java.util.Objects;

public record IncrementalSyncCursor(Instant modifiedAfter) {
  public IncrementalSyncCursor {
    modifiedAfter = Objects.requireNonNull(modifiedAfter, "modifiedAfter는 필수입니다.");
  }
}
