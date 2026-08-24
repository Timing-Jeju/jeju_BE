package com.timingjeju.api.application.retention;

public interface SnapshotRetentionPort {
  SnapshotRetentionResult execute(SnapshotRetentionCommand command);
}
