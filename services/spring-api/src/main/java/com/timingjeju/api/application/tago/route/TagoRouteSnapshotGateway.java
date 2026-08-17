package com.timingjeju.api.application.tago.route;

import java.util.UUID;

public interface TagoRouteSnapshotGateway {
  SavedTagoRoutePayload save(
      UUID runId,
      String kind,
      String cityCode,
      String routeId,
      int pageNo,
      TagoRouteSourceResponse response);

  void markParsed(SavedTagoRoutePayload payload);

  void markRejected(SavedTagoRoutePayload payload);
}
