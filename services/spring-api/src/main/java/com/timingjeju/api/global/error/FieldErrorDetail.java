package com.timingjeju.api.global.error;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "검증에 실패한 필드와 안전한 사용자 안내")
public record FieldErrorDetail(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String field,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String detail) {

  public FieldErrorDetail {
    if (field == null || field.isBlank()) {
      throw new IllegalArgumentException("field must not be blank");
    }
    if (detail == null || detail.isBlank()) {
      throw new IllegalArgumentException("detail must not be blank");
    }
  }
}
