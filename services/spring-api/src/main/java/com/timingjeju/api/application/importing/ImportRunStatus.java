package com.timingjeju.api.application.importing;

public enum ImportRunStatus {
  PARTIAL("partial"),
  FAILED("failed"),
  CANCELLED("cancelled"),
  SUCCEEDED("succeeded");

  private final String databaseValue;

  ImportRunStatus(String databaseValue) {
    this.databaseValue = databaseValue;
  }

  public String databaseValue() {
    return databaseValue;
  }
}
