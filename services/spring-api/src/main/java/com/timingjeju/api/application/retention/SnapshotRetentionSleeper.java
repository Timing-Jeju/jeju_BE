package com.timingjeju.api.application.retention;

import java.time.Duration;

@FunctionalInterface
public interface SnapshotRetentionSleeper {
  void sleep(Duration duration) throws InterruptedException;
}
