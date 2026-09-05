package com.timingjeju.api.application.trip;

@FunctionalInterface
public interface TripAggregateMutationEffect {
  TripAggregateMutationEffect NONE = () -> {};

  void apply();
}
