package com.timingjeju.api.application.idempotency;

@FunctionalInterface
public interface IdempotencyOperation {
  IdempotencyResponse execute();
}
