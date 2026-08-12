package com.timingjeju.api.global.externalapi;

final class ExternalApiCircuitOpenException extends RuntimeException {
  ExternalApiCircuitOpenException() {
    super("외부 API circuit open");
  }
}
