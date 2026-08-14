package com.timingjeju.api.application.snapshot;

import java.util.UUID;

@FunctionalInterface
public interface SnapshotIdentityGenerator {
  UUID newSnapshotId();
}
