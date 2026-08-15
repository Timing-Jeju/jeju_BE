package com.timingjeju.api.application.tourapi.place;

import java.time.Instant;
import java.util.Objects;

public record PlaceListWrite(TourPlace place, Instant seenAt, PlaceLineage lineage) {
  public PlaceListWrite {
    place = Objects.requireNonNull(place, "place는 필수입니다.");
    seenAt = Objects.requireNonNull(seenAt, "seenAt은 필수입니다.");
    lineage = Objects.requireNonNull(lineage, "lineage는 필수입니다.");
  }
}
