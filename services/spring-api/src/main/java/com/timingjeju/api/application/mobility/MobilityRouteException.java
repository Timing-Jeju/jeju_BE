package com.timingjeju.api.application.mobility;

public final class MobilityRouteException extends RuntimeException {
  public enum Code {
    INVALID_REQUEST,
    INVALID_PROVIDER_RESPONSE,
    RATE_LIMITED,
    TIMEOUT,
    PROVIDER_UNAVAILABLE,
    EXTERNAL_FACTS_UNAVAILABLE
  }

  private final Code code;

  private MobilityRouteException(Code code) {
    super(code.name(), null, false, false);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public boolean recoverable() {
    return switch (code) {
      case RATE_LIMITED, TIMEOUT, PROVIDER_UNAVAILABLE -> true;
      case INVALID_REQUEST, INVALID_PROVIDER_RESPONSE, EXTERNAL_FACTS_UNAVAILABLE -> false;
    };
  }

  public static MobilityRouteException invalidRequest() {
    return new MobilityRouteException(Code.INVALID_REQUEST);
  }

  public static MobilityRouteException invalidProviderResponse() {
    return new MobilityRouteException(Code.INVALID_PROVIDER_RESPONSE);
  }

  public static MobilityRouteException rateLimited() {
    return new MobilityRouteException(Code.RATE_LIMITED);
  }

  public static MobilityRouteException timeout() {
    return new MobilityRouteException(Code.TIMEOUT);
  }

  public static MobilityRouteException providerUnavailable() {
    return new MobilityRouteException(Code.PROVIDER_UNAVAILABLE);
  }

  public static MobilityRouteException externalFactsUnavailable() {
    return new MobilityRouteException(Code.EXTERNAL_FACTS_UNAVAILABLE);
  }
}
