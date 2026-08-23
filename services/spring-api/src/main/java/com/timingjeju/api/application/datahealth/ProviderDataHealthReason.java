package com.timingjeju.api.application.datahealth;

public enum ProviderDataHealthReason {
  PROVIDER_DISABLED,
  NO_SUCCESSFUL_IMPORT,
  VALID_FACTS_WINDOW_EXHAUSTED,
  HEALTHY,
  TTL_EXPIRED,
  LATEST_RUN_FAILED
}
