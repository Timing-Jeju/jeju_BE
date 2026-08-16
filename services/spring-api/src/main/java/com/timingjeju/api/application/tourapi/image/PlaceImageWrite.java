package com.timingjeju.api.application.tourapi.image;

import java.util.Objects;

public record PlaceImageWrite(PlaceImage image, PlaceImagePageLineage pageLineage) {
  public PlaceImageWrite {
    image = Objects.requireNonNull(image, "image는 필수입니다.");
    pageLineage = Objects.requireNonNull(pageLineage, "pageLineage는 필수입니다.");
  }
}
