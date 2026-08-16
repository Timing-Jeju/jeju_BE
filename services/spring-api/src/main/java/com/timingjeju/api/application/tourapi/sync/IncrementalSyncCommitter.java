package com.timingjeju.api.application.tourapi.sync;

public interface IncrementalSyncCommitter {
  IncrementalSyncCommitResult commit(IncrementalSyncCommitCommand command);
}
