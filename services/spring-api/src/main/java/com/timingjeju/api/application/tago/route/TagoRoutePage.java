package com.timingjeju.api.application.tago.route;

import java.util.List;

public record TagoRoutePage(int pageNo, int numOfRows, int totalCount, List<TagoRoute> routes) {
  public TagoRoutePage {
    routes = List.copyOf(routes);
  }
}
