package com.timingjeju.api.domain.accountdeletion.worker;

public enum DeletionStep {
  SESSIONS_REVOKED,
  ACCOUNT_REQUESTS_DENIED,
  PROFILE_IMAGES_DELETED,
  APP_DATA_ERASED,
  AUTH_USER_DELETED
}
