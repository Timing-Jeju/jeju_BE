package com.timingjeju.api.application.tago.route;

public interface TagoRouteSource {
  TagoRouteSourceResponse fetchRouteList(String cityCode, String routeNo, int pageNo);

  TagoRouteSourceResponse fetchRouteDetail(String cityCode, String routeId);

  TagoRouteSourceResponse fetchRouteStops(String cityCode, String routeId, int pageNo);
}
