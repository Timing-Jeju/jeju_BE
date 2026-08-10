package com.timingjeju.api.global.idempotency;

import com.timingjeju.api.application.idempotency.IdempotencyAttemptTokenGenerator;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class UuidIdempotencyAttemptTokenGenerator
    implements IdempotencyAttemptTokenGenerator {

  @Override
  public UUID generate() {
    return UUID.randomUUID();
  }
}
