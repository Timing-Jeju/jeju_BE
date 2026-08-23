package com.timingjeju.api.application.tago.arrival;

import java.util.Objects;
import java.util.Optional;

public record TagoArrivalProcessResult(
    TagoArrivalSnapshot rawSnapshot, TagoArrivalException.Code rawFailure) {
  public TagoArrivalProcessResult {
    if ((rawSnapshot == null) == (rawFailure == null)) {
      throw new IllegalArgumentException("arrival process 결과 조합이 올바르지 않습니다.");
    }
  }

  public static TagoArrivalProcessResult success(TagoArrivalSnapshot snapshot) {
    return new TagoArrivalProcessResult(Objects.requireNonNull(snapshot), null);
  }

  public static TagoArrivalProcessResult failure(TagoArrivalException.Code code) {
    return new TagoArrivalProcessResult(null, Objects.requireNonNull(code));
  }

  public Optional<TagoArrivalSnapshot> snapshot() {
    return Optional.ofNullable(rawSnapshot);
  }

  public Optional<TagoArrivalException.Code> failure() {
    return Optional.ofNullable(rawFailure);
  }
}
