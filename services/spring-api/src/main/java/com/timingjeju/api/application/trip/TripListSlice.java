package com.timingjeju.api.application.trip;

import java.util.List;

public record TripListSlice(List<TripSummary> rows) {
  public TripListSlice {
    rows = List.copyOf(rows);
  }
}
