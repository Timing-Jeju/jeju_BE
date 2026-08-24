package com.timingjeju.api.application.retention;

@FunctionalInterface
public interface SnapshotRetentionCycleMetrics {
  void record(SnapshotRetentionCycleResult result);
}
