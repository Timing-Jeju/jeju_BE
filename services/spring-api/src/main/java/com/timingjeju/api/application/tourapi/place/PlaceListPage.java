package com.timingjeju.api.application.tourapi.place;

import java.util.List;
import java.util.Map;

public record PlaceListPage(
    int pageNo,
    int numOfRows,
    int totalCount,
    int rawItemCount,
    List<TourPlace> places,
    Map<PlaceRejectReason, Integer> rejectedReasons) {

  public PlaceListPage {
    if (pageNo < 1 || numOfRows < 1 || totalCount < 0 || rawItemCount < 0) {
      throw PlaceListImportException.invalidResponse();
    }
    places = List.copyOf(places);
    rejectedReasons = Map.copyOf(rejectedReasons);
    int rejected = rejectedReasons.values().stream().mapToInt(Integer::intValue).sum();
    if (places.size() + rejected != rawItemCount || rawItemCount > numOfRows) {
      throw PlaceListImportException.invalidResponse();
    }
  }
}
