package com.timingjeju.api.application.push;

import java.time.Duration;
import java.util.Objects;

public sealed interface PushSendResult
    permits PushSendResult.Accepted,
        PushSendResult.RetryableFailure,
        PushSendResult.AcceptanceUnknown,
        PushSendResult.PermanentFailure {

  record Accepted(String providerMessageId) implements PushSendResult {
    public Accepted {
      if (providerMessageId == null || providerMessageId.isBlank()) {
        throw new IllegalArgumentException("provider message id는 필수입니다.");
      }
    }
  }

  record RetryableFailure(PushErrorClass errorClass, Duration retryAfter)
      implements PushSendResult {
    public RetryableFailure {
      Objects.requireNonNull(errorClass, "errorClass");
      if (retryAfter != null && (retryAfter.isNegative() || retryAfter.isZero())) {
        throw new IllegalArgumentException("retryAfter는 양수여야 합니다.");
      }
    }
  }

  record AcceptanceUnknown(PushErrorClass errorClass) implements PushSendResult {
    public AcceptanceUnknown {
      Objects.requireNonNull(errorClass, "errorClass");
    }
  }

  record PermanentFailure(PushErrorClass errorClass, boolean invalidateToken)
      implements PushSendResult {
    public PermanentFailure {
      Objects.requireNonNull(errorClass, "errorClass");
    }
  }
}
