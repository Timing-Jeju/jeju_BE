package com.timingjeju.api.application.idempotency;

public interface IdempotencyUseCase {
  IdempotencyResponse execute(IdempotencyRequest request, IdempotencyOperation operation);
}
