package com.timingjeju.api.application.tago.stop;

public record TagoStopImportCommand(String idempotencyKey) {
  public TagoStopImportCommand {
    if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
      throw TagoStopImportException.invalidRequest();
    }
  }
}
