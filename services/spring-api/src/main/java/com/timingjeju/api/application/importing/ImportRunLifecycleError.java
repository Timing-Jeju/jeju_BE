package com.timingjeju.api.application.importing;

public enum ImportRunLifecycleError {
  SCOPE_ALREADY_RUNNING,
  INVALID_PARENT,
  INVALID_REQUEST,
  NOT_FOUND,
  OWNERSHIP_LOST,
  INVALID_TRANSITION,
  COUNT_OVERFLOW
}
