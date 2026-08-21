package com.timingjeju.api.application.staypolicy;

public enum RecommendedStaySource {
  PLACE_OVERRIDE("place_override"),
  CATEGORY_DEFAULT("category_default"),
  UNAVAILABLE("unavailable");

  private final String value;

  RecommendedStaySource(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}
