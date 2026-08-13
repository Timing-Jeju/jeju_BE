package com.timingjeju.api.global.externalapi;

public final class ExternalApiException extends RuntimeException {

  private final ExternalApiFailureCode code;

  ExternalApiException(ExternalApiFailureCode code) {
    super(message(code), null, false, false);
    this.code = code;
  }

  public ExternalApiFailureCode code() {
    return code;
  }

  private static String message(ExternalApiFailureCode code) {
    return switch (code) {
      case PROVIDER_NOT_CONFIGURED -> "외부 API provider가 활성화되지 않았습니다.";
      case CIRCUIT_OPEN -> "외부 API 회로가 열려 요청을 일시적으로 중단했습니다.";
      case CONNECT_FAILURE, CONNECTION_RESET, TRANSPORT_ERROR -> "외부 API 연결을 완료하지 못했습니다.";
      case CONNECT_TIMEOUT, READ_TIMEOUT, TOTAL_TIMEOUT -> "외부 API 응답 제한 시간을 초과했습니다.";
      case HTTP_STATUS, RETRY_EXHAUSTED -> "외부 API가 요청을 처리하지 못했습니다.";
      case REDIRECT_NOT_ALLOWED -> "외부 API redirect 응답을 허용하지 않습니다.";
      case UNSUPPORTED_CONTENT_TYPE, UNSUPPORTED_CONTENT_ENCODING -> "외부 API 응답 형식을 확인할 수 없습니다.";
      case RESPONSE_TOO_LARGE -> "외부 API 응답 크기 제한을 초과했습니다.";
      case MALFORMED_RESPONSE -> "외부 API 응답을 해석할 수 없습니다.";
    };
  }
}
