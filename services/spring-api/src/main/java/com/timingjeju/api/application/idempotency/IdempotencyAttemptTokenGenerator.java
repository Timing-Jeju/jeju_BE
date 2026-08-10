package com.timingjeju.api.application.idempotency;

import java.util.UUID;

@FunctionalInterface
public interface IdempotencyAttemptTokenGenerator {
  UUID generate();
}
