package com.timingjeju.api.application.tourapi.detail;

import java.time.Instant;
import java.util.Objects;

public record PlaceDetailCommon(
    String contentId,
    String contentTypeId,
    String phone,
    String homepageUrl,
    String overviewRaw,
    String overviewPlainText,
    Instant sourceModifiedAt) {

  public PlaceDetailCommon {
    contentId = requireText(contentId, "contentId");
    contentTypeId = requireText(contentTypeId, "contentTypeId");
    if ((overviewRaw == null) != (overviewPlainText == null)) {
      throw new IllegalArgumentException("overview 원문과 plain text는 함께 있어야 합니다.");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + "는 비어 있을 수 없습니다.");
    }
    return Objects.requireNonNull(value).strip();
  }
}
