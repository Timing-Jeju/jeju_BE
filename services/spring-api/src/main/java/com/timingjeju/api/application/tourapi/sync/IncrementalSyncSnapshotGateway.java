package com.timingjeju.api.application.tourapi.sync;

import java.util.UUID;

public interface IncrementalSyncSnapshotGateway {
  SavedIncrementalSyncPage save(
      UUID runId, IncrementalSyncCursor cursor, int pageNo, IncrementalSyncSourceResponse response);

  void markParsed(SavedIncrementalSyncPage page);

  void markRejected(SavedIncrementalSyncPage page);
}
