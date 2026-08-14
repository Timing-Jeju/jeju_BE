package com.timingjeju.api.application.snapshot;

public enum SnapshotStatus {
  RECEIVED("received"),
  PARSED("parsed"),
  REJECTED("rejected"),
  IGNORED("ignored"),
  TOMBSTONED("tombstoned");

  private final String databaseValue;

  SnapshotStatus(String databaseValue) {
    this.databaseValue = databaseValue;
  }

  public String databaseValue() {
    return databaseValue;
  }

  public boolean terminal() {
    return this != RECEIVED;
  }
}
