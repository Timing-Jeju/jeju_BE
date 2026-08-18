package com.timingjeju.api.application.tago.route;

import java.util.List;

public record TagoRouteStopPage(
    int pageNo, int numOfRows, int totalCount, List<TagoRouteStop> stops) {
  public TagoRouteStopPage {
    stops = List.copyOf(stops);
  }
}
