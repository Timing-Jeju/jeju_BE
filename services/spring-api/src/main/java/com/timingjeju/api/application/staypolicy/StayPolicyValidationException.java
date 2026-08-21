package com.timingjeju.api.application.staypolicy;

import java.util.List;

public final class StayPolicyValidationException extends RuntimeException {
  public StayPolicyValidationException(List<String> violations) {
    super("Stay policy payload validation failed: " + String.join("; ", violations));
  }
}
