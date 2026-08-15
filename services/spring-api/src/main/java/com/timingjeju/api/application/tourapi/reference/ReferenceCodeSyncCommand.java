package com.timingjeju.api.application.tourapi.reference;

import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

public record ReferenceCodeSyncCommand(
    ReferenceCodeOperation operation,
    LocalDate validFrom,
    LocalDate validTo,
    String idempotencyKey) {

  private static final Pattern SAFE_IDEMPOTENCY_KEY =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

  public ReferenceCodeSyncCommand {
    operation = Objects.requireNonNull(operation, "operation은 필수입니다.");
    validFrom = Objects.requireNonNull(validFrom, "validFrom은 필수입니다.");
    if (validTo != null && validTo.isBefore(validFrom)) {
      throw new IllegalArgumentException("validTo가 validFrom보다 빠릅니다.");
    }
    if (idempotencyKey == null || !SAFE_IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
      throw new IllegalArgumentException("idempotencyKey 형식이 올바르지 않습니다.");
    }
  }
}
