package com.timingjeju.api.application.kma;

public final class KmaWeatherImportException extends RuntimeException {

  private final KmaWeatherImportError code;

  private KmaWeatherImportException(KmaWeatherImportError code) {
    super(code.name());
    this.code = code;
  }

  public static KmaWeatherImportException invalidResponse() {
    return new KmaWeatherImportException(KmaWeatherImportError.INVALID_PROVIDER_RESPONSE);
  }

  public static KmaWeatherImportException unsupportedCategory() {
    return new KmaWeatherImportException(KmaWeatherImportError.UNSUPPORTED_CATEGORY);
  }

  public static KmaWeatherImportException providerUnavailable() {
    return new KmaWeatherImportException(KmaWeatherImportError.PROVIDER_UNAVAILABLE);
  }

  public static KmaWeatherImportException storageFailure() {
    return new KmaWeatherImportException(KmaWeatherImportError.STORAGE_FAILURE);
  }

  public static KmaWeatherImportException invalidReplay() {
    return new KmaWeatherImportException(KmaWeatherImportError.INVALID_REPLAY);
  }

  public KmaWeatherImportError code() {
    return code;
  }
}
