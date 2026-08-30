package com.timingjeju.api.application.trip;

import java.util.List;

public record TripPage(List<TripSummary> items, int size, boolean hasNext, String nextCursor) {
  public TripPage {
    items = List.copyOf(items);
  }
}
