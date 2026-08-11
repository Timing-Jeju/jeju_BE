package com.timingjeju.api.application.asyncrun;

public enum RunResultSource {
  COMPUTED("computed"),
  FALLBACK("fallback");

  private final String databaseValue;

  RunResultSource(String databaseValue) {
    this.databaseValue = databaseValue;
  }

  public String databaseValue() {
    return databaseValue;
  }
}
