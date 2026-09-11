package com.timingjeju.api.application.accommodation;

public record AccommodationPatchValue<T>(boolean present, T value) {
  public AccommodationPatchValue {
    if (!present && value != null) {
      throw new IllegalArgumentException("생략 값에는 value가 없어야 합니다.");
    }
  }

  public static <T> AccommodationPatchValue<T> present(T value) {
    return new AccommodationPatchValue<>(true, value);
  }

  public static <T> AccommodationPatchValue<T> omitted() {
    return new AccommodationPatchValue<>(false, null);
  }
}
