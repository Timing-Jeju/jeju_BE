package com.timingjeju.api.application.importing;

public enum ImportSourceKind {
  FIXTURE("fixture"),
  TOUR_API("tour_api"),
  TAGO("tago"),
  JEJU_BIS("jeju_bis"),
  WEATHER_API("weather_api"),
  DIRECTIONS_API("directions_api"),
  ADMIN_UPLOAD("admin_upload");

  private final String databaseValue;

  ImportSourceKind(String databaseValue) {
    this.databaseValue = databaseValue;
  }

  public String databaseValue() {
    return databaseValue;
  }
}
