package com.timingjeju.api.application.datahealth;

public enum ProviderDataHealthAttemptStatus {
  SUCCEEDED,
  FAILED,
  PARTIAL,
  CANCELLED;

  public static ProviderDataHealthAttemptStatus fromDatabase(String value) {
    return switch (value) {
      case "succeeded" -> SUCCEEDED;
      case "failed" -> FAILED;
      case "partial" -> PARTIAL;
      case "cancelled" -> CANCELLED;
      default -> throw ProviderDataHealthException.unavailable();
    };
  }
}
