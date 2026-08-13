package com.timingjeju.api.global.externalapi;

public enum ExternalApiFailureCode {
  PROVIDER_NOT_CONFIGURED("configuration_error"),
  CIRCUIT_OPEN("circuit_open"),
  CONNECT_TIMEOUT("timeout"),
  CONNECT_FAILURE("transport_error"),
  READ_TIMEOUT("timeout"),
  TOTAL_TIMEOUT("timeout"),
  CONNECTION_RESET("transport_error"),
  TRANSPORT_ERROR("transport_error"),
  HTTP_STATUS("http_error"),
  RETRY_EXHAUSTED("retry_exhausted"),
  REDIRECT_NOT_ALLOWED("invalid_response"),
  UNSUPPORTED_CONTENT_TYPE("invalid_response"),
  UNSUPPORTED_CONTENT_ENCODING("invalid_response"),
  RESPONSE_TOO_LARGE("invalid_response"),
  MALFORMED_RESPONSE("invalid_response");

  private final String metricResult;

  ExternalApiFailureCode(String metricResult) {
    this.metricResult = metricResult;
  }

  String metricResult() {
    return metricResult;
  }
}
