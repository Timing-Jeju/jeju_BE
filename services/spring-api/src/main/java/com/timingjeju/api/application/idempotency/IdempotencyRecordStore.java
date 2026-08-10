package com.timingjeju.api.application.idempotency;

import java.time.Instant;
import java.util.UUID;

public interface IdempotencyRecordStore {

  IdempotencyAcquisition acquire(IdempotencyScope scope, String requestHash, Instant now);

  void complete(
      IdempotencyScope scope,
      String requestHash,
      UUID attemptToken,
      IdempotencyResponse response,
      Instant now);

  void release(IdempotencyScope scope, String requestHash, UUID attemptToken);
}
