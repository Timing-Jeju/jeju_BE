package com.timingjeju.api.application.trip;

import java.time.Instant;

@FunctionalInterface
public interface TripAggregateTimestampedMutationOperation<T> {
  TripAggregateMutationPlan<T> apply(TripAggregateMutationState state, Instant committedAt);
}
