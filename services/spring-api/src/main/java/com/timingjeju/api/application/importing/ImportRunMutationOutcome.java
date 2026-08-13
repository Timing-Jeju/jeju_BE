package com.timingjeju.api.application.importing;

public enum ImportRunMutationOutcome {
  UPDATED,
  NOT_FOUND,
  OWNERSHIP_LOST,
  INVALID_TRANSITION,
  COUNT_OVERFLOW
}
