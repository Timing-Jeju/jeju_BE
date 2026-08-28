package com.timingjeju.api.application.notification;

/** Provider-neutral signal that registration-token protection or recovery is unavailable. */
public final class RegistrationTokenProtectionFailure extends RuntimeException {

  public RegistrationTokenProtectionFailure() {
    super(null, null, false, false);
  }
}
