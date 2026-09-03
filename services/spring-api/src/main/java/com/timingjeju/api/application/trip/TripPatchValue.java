package com.timingjeju.api.application.trip;

import java.util.Objects;

public record TripPatchValue<T>(boolean present, T value) {
  public TripPatchValue {
    if (present) {
      Objects.requireNonNull(value);
    } else if (value != null) {
      throw new IllegalArgumentException("생략 값에는 value가 없어야 합니다.");
    }
  }

  public static <T> TripPatchValue<T> present(T value) {
    return new TripPatchValue<>(true, value);
  }

  public static <T> TripPatchValue<T> omitted() {
    return new TripPatchValue<>(false, null);
  }
}
