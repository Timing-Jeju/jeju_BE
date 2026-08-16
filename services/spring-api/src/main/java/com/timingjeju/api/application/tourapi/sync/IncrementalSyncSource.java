package com.timingjeju.api.application.tourapi.sync;

public interface IncrementalSyncSource {
  IncrementalSyncSourceResponse fetch(IncrementalSyncCursor cursor, int pageNo);
}
