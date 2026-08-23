package com.timingjeju.api.application.tago.arrival;

import com.timingjeju.api.application.importing.ImportRunLease;
import java.time.Instant;

public interface TagoArrivalImportSession {
  ImportRunLease start(TagoArrivalCacheKey key, Instant observedAt);

  void fail(ImportRunLease lease, TagoArrivalException.Code code);
}
