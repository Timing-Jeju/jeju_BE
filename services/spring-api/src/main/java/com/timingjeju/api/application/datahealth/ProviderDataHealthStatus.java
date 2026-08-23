package com.timingjeju.api.application.datahealth;

public enum ProviderDataHealthStatus {
  DISABLED,
  NEVER_SYNCED,
  NO_RECENT_VALID_FACTS,
  FRESH,
  STALE,
  LAST_ATTEMPT_FAILED
}
