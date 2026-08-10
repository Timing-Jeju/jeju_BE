package com.timingjeju.api.application.idempotency;

import java.util.function.Supplier;

public interface IdempotencyTransactions {

  <T> T requiresNew(Supplier<T> work);

  <T> T required(Supplier<T> work);
}
