package com.timingjeju.api.application.tago.stop;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TagoStopRepository {
  TagoStopWriteResult apply(
      TagoCityCode city,
      TagoStopPageLineage cityLineage,
      TagoStopPageLineage stationSweepLineage,
      List<TagoStopWrite> stations,
      UUID runId,
      Instant observedAt);
}
