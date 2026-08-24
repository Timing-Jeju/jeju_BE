package com.timingjeju.api.domain.trip.dto.response;

import com.timingjeju.api.application.trip.TripPage;
import java.util.List;

public record TripListResponse(List<TripSummaryResponse> items, Page page) {
  public record Page(int size, boolean hasNext, String nextCursor) {}

  public static TripListResponse from(TripPage source) {
    return new TripListResponse(
        source.items().stream().map(TripSummaryResponse::from).toList(),
        new Page(source.size(), source.hasNext(), source.nextCursor()));
  }
}
