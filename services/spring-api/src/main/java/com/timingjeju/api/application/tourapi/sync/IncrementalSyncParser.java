package com.timingjeju.api.application.tourapi.sync;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;

public interface IncrementalSyncParser {
  IncrementalSyncPage parse(SnapshotPayloadFormat format, byte[] payload);
}
