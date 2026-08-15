package com.timingjeju.api.application.snapshot;

public enum SnapshotMutationOutcome {
  UPDATED,
  ALREADY_AT_TARGET,
  NOT_FOUND,
  INVALID_TRANSITION
}
