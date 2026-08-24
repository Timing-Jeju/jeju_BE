package com.timingjeju.api.application.legal;

public final class LegalProfileException extends RuntimeException {

  private final String code;

  private LegalProfileException(String code) {
    super(null, null, false, false);
    this.code = code;
  }

  public static LegalProfileException invalidRequest() {
    return new LegalProfileException("INVALID_PROFILE_LEGAL_REQUEST");
  }

  public static LegalProfileException consentRequired() {
    return new LegalProfileException("LEGAL_CONSENT_REQUIRED");
  }

  public static LegalProfileException dataUnavailable() {
    return new LegalProfileException("PROFILE_DATA_UNAVAILABLE");
  }

  public String code() {
    return code;
  }
}
