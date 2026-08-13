package com.timingjeju.api.application.importing;

public enum ImportSyncMode {
  FULL("full"),
  INCREMENTAL("incremental"),
  LAZY("lazy"),
  SNAPSHOT("snapshot");

  private final String databaseValue;

  ImportSyncMode(String databaseValue) {
    this.databaseValue = databaseValue;
  }

  public String databaseValue() {
    return databaseValue;
  }
}
