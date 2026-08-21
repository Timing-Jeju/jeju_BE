package com.timingjeju.api.domain.places.model;

import java.util.List;
import java.util.UUID;

public record PlaceDetailSnapshot(
    UUID placeId,
    String contentId,
    String name,
    String category,
    String regionCode,
    String regionLabel,
    String address,
    double latitude,
    double longitude,
    String overview,
    String phone,
    String homepageUrl,
    String operatingHoursText,
    String closedDaysText,
    String parkingText,
    String admissionFeeText,
    List<PlaceDetailImageRow> images,
    boolean saved,
    String memo,
    List<String> tags) {

  public PlaceDetailSnapshot {
    images = List.copyOf(images);
    tags = List.copyOf(tags);
  }
}
