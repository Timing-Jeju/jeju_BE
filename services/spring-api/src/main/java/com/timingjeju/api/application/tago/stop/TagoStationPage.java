package com.timingjeju.api.application.tago.stop;

import java.util.List;

public record TagoStationPage(
    int pageNo, int numOfRows, int totalCount, List<TagoStation> stations) {
  public TagoStationPage {
    stations = List.copyOf(stations);
  }
}
