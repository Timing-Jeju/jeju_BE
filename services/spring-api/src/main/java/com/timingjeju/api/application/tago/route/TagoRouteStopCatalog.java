package com.timingjeju.api.application.tago.route;

import java.util.Set;

public interface TagoRouteStopCatalog {
  void requireExisting(String provider, String service, String cityCode, Set<String> nodeIds);
}
