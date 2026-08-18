package com.timingjeju.api.application.demo;

import java.util.List;
import java.util.UUID;

public interface DemoStorageReader {
  DemoStorageView latest();

  List<DemoPlaceRow> candidates(UUID listRunId, String... contentTypeIds);

  DemoSweepStats sweepStats(UUID importRunId, String operation);
}
