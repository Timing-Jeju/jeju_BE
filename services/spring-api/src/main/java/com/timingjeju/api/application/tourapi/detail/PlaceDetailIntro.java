package com.timingjeju.api.application.tourapi.detail;

import java.util.Map;

public record PlaceDetailIntro(
    String contentId,
    String contentTypeId,
    String phone,
    String operatingHoursText,
    String closedDaysText,
    String parkingText,
    String petPolicyText,
    String admissionFeeText,
    String facilitiesText,
    String reservationInfoText,
    String accessibilityText,
    Map<String, String> introAttributes) {

  public PlaceDetailIntro {
    if (contentId == null
        || contentId.isBlank()
        || contentTypeId == null
        || contentTypeId.isBlank()) {
      throw new IllegalArgumentException("contentId와 contentTypeId는 필수입니다.");
    }
    introAttributes = Map.copyOf(introAttributes);
  }
}
