package com.timingjeju.api.application.tago.route;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TagoRouteRepository {
  TagoRouteWriteResult apply(
      List<TagoRouteWrite> routes,
      List<TagoRouteStopWrite> routeStops,
      UUID runId,
      Instant observedAt);
}
