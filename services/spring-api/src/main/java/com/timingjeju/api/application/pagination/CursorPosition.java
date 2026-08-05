package com.timingjeju.api.application.pagination;

public record CursorPosition(String sortValue, String tieBreaker) {

  public CursorPosition {
    sortValue = requireText(sortValue, "sortValue");
    tieBreaker = requireText(tieBreaker, "tieBreaker");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
