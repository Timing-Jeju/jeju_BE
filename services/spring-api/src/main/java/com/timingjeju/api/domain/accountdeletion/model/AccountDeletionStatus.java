package com.timingjeju.api.domain.accountdeletion.model;

public enum AccountDeletionStatus {
  QUEUED,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }

  public String wireValue() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
