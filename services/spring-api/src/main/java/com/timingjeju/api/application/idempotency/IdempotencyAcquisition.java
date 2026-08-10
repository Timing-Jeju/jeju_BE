package com.timingjeju.api.application.idempotency;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record IdempotencyAcquisition(
    Disposition disposition, Optional<UUID> attemptToken, Optional<IdempotencyResponse> response) {

  public enum Disposition {
    ACQUIRED,
    REPLAY,
    REUSED,
    PROCESSING
  }

  public IdempotencyAcquisition {
    Objects.requireNonNull(disposition, "disposition must not be null");
    attemptToken = Objects.requireNonNull(attemptToken, "attemptToken must not be null");
    response = Objects.requireNonNull(response, "response must not be null");
    if ((disposition == Disposition.ACQUIRED) != attemptToken.isPresent()) {
      throw new IllegalArgumentException("ACQUIRED disposition만 attempt token을 포함해야 합니다.");
    }
    if ((disposition == Disposition.REPLAY) != response.isPresent()) {
      throw new IllegalArgumentException("REPLAY disposition만 response를 포함해야 합니다.");
    }
  }

  public static IdempotencyAcquisition acquired(UUID attemptToken) {
    return new IdempotencyAcquisition(
        Disposition.ACQUIRED, Optional.of(attemptToken), Optional.empty());
  }

  public static IdempotencyAcquisition replay(IdempotencyResponse response) {
    return new IdempotencyAcquisition(Disposition.REPLAY, Optional.empty(), Optional.of(response));
  }

  public static IdempotencyAcquisition reused() {
    return new IdempotencyAcquisition(Disposition.REUSED, Optional.empty(), Optional.empty());
  }

  public static IdempotencyAcquisition processing() {
    return new IdempotencyAcquisition(Disposition.PROCESSING, Optional.empty(), Optional.empty());
  }
}
