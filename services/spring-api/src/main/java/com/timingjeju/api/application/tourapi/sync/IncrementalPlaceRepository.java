package com.timingjeju.api.application.tourapi.sync;

import java.util.List;

public interface IncrementalPlaceRepository {
  IncrementalPlaceWriteResult apply(List<IncrementalSyncWrite> writes);
}
