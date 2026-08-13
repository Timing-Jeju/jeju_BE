package com.timingjeju.api.application.snapshot;

public interface SnapshotStore {
  SnapshotSaveResult save(StoredSnapshot snapshot);

  SnapshotMutationOutcome transition(SnapshotStateMutation mutation);
}
