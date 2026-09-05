package com.timingjeju.api.application.trip;

@FunctionalInterface
public interface TripAggregateMutationOperation<T> {
  TripAggregateMutationPlan<T> apply(TripAggregateMutationState state);
}
