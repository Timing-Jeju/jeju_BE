package com.timingjeju.api.domain.savedplaces.model;

public record PresentValue<T>(boolean present, T value) {
  public static <T> PresentValue<T> omitted() {
    return new PresentValue<>(false, null);
  }

  public static <T> PresentValue<T> of(T value) {
    return new PresentValue<>(true, value);
  }
}
