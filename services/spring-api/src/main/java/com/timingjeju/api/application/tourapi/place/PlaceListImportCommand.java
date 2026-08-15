package com.timingjeju.api.application.tourapi.place;

import java.util.regex.Pattern;

public record PlaceListImportCommand(String idempotencyKey) {
  private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

  public PlaceListImportCommand {
    if (idempotencyKey == null || !SAFE_KEY.matcher(idempotencyKey).matches()) {
      throw new IllegalArgumentException("idempotencyKey 형식이 올바르지 않습니다.");
    }
  }
}
