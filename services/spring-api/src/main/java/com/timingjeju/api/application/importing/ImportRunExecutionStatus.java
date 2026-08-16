package com.timingjeju.api.application.importing;

public enum ImportRunExecutionStatus {
  RUNNING,
  SUCCEEDED,
  FAILED,
  PARTIAL,
  CANCELLED;

  public static ImportRunExecutionStatus fromDatabaseValue(String value) {
    return switch (value) {
      case "running" -> RUNNING;
      case "succeeded" -> SUCCEEDED;
      case "failed" -> FAILED;
      case "partial" -> PARTIAL;
      case "cancelled" -> CANCELLED;
      default -> throw new IllegalArgumentException("지원하지 않는 import run 상태입니다.");
    };
  }
}
