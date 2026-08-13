package com.timingjeju.api.application.snapshot;

import java.time.Duration;

public final class SnapshotRetention {
  public static final Duration SUCCESSFUL = Duration.ofDays(30);
  public static final Duration FAILED_OR_UNPARSED = Duration.ofDays(7);

  private SnapshotRetention() {}
}
