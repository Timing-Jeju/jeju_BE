package com.timingjeju.api.application.tago.stop;

import java.util.UUID;

public interface TagoStopSnapshotGateway {
  SavedTagoStopPage saveCity(UUID runId, TagoStopSourceResponse response);

  SavedTagoStopPage saveStations(
      UUID runId, String cityCode, int pageNo, TagoStopSourceResponse response);

  void markParsed(SavedTagoStopPage page);

  void markRejected(SavedTagoStopPage page);
}
