package com.timingjeju.api.application.idempotency;

import java.time.Clock;
import java.util.Objects;

public final class TransactionalIdempotencyService implements IdempotencyUseCase {

  private final IdempotencyRecordStore store;
  private final IdempotencyTransactions transactions;
  private final Clock clock;

  public TransactionalIdempotencyService(
      IdempotencyRecordStore store, IdempotencyTransactions transactions, Clock clock) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public IdempotencyResponse execute(IdempotencyRequest request, IdempotencyOperation operation) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    IdempotencyAcquisition acquisition =
        transactions.requiresNew(
            () -> store.acquire(request.scope(), request.requestHash(), clock.instant()));

    return switch (acquisition.disposition()) {
      case REPLAY -> acquisition.response().orElseThrow();
      case REUSED -> throw IdempotencyException.reused();
      case PROCESSING -> throw IdempotencyException.processing();
      case ACQUIRED ->
          executeAcquired(request, acquisition.attemptToken().orElseThrow(), operation);
    };
  }

  private IdempotencyResponse executeAcquired(
      IdempotencyRequest request, java.util.UUID attemptToken, IdempotencyOperation operation) {
    try {
      return transactions.required(
          () -> {
            IdempotencyResponse response = Objects.requireNonNull(operation.execute());
            if (response.isUnexpectedServerError()) {
              throw new UncacheableResponse(response);
            }
            store.complete(
                request.scope(), request.requestHash(), attemptToken, response, clock.instant());
            return response;
          });
    } catch (UncacheableResponse failure) {
      release(request, attemptToken);
      return failure.response;
    } catch (RuntimeException | Error failure) {
      release(request, attemptToken);
      throw failure;
    }
  }

  private void release(IdempotencyRequest request, java.util.UUID attemptToken) {
    transactions.requiresNew(
        () -> {
          store.release(request.scope(), request.requestHash(), attemptToken);
          return null;
        });
  }

  private static final class UncacheableResponse extends RuntimeException {
    private final IdempotencyResponse response;

    private UncacheableResponse(IdempotencyResponse response) {
      super(null, null, false, false);
      this.response = response;
    }
  }
}
