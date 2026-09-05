package com.timingjeju.api.application.trip;

import java.util.Objects;

public record TripAggregateMutationPlan<T>(
    TripRootPatch rootPatch,
    TripScheduleEffect scheduleEffect,
    TripAggregateMutationEffect beforeRootEffect,
    TripAggregateMutationEffect effect,
    T payload) {
  public TripAggregateMutationPlan {
    Objects.requireNonNull(rootPatch);
    Objects.requireNonNull(scheduleEffect);
    Objects.requireNonNull(beforeRootEffect);
    Objects.requireNonNull(effect);
    if (scheduleEffect == TripScheduleEffect.NONE
        && (!rootPatch.equals(TripRootPatch.unchanged())
            || beforeRootEffect != TripAggregateMutationEffect.NONE
            || effect != TripAggregateMutationEffect.NONE)) {
      throw new IllegalArgumentException("no-change plan cannot carry mutations");
    }
  }

  public static <T> TripAggregateMutationPlan<T> noChange(T payload) {
    return new TripAggregateMutationPlan<>(
        TripRootPatch.unchanged(),
        TripScheduleEffect.NONE,
        TripAggregateMutationEffect.NONE,
        TripAggregateMutationEffect.NONE,
        payload);
  }

  public static <T> TripAggregateMutationPlan<T> maintain(TripRootPatch rootPatch, T payload) {
    return maintain(rootPatch, TripAggregateMutationEffect.NONE, payload);
  }

  public static <T> TripAggregateMutationPlan<T> maintain(
      TripRootPatch rootPatch, TripAggregateMutationEffect effect, T payload) {
    return maintain(rootPatch, TripAggregateMutationEffect.NONE, effect, payload);
  }

  public static <T> TripAggregateMutationPlan<T> maintain(
      TripRootPatch rootPatch,
      TripAggregateMutationEffect beforeRootEffect,
      TripAggregateMutationEffect effect,
      T payload) {
    return new TripAggregateMutationPlan<>(
        rootPatch, TripScheduleEffect.MAINTAIN, beforeRootEffect, effect, payload);
  }

  public static <T> TripAggregateMutationPlan<T> invalidate(TripRootPatch rootPatch, T payload) {
    return invalidate(rootPatch, TripAggregateMutationEffect.NONE, payload);
  }

  public static <T> TripAggregateMutationPlan<T> invalidate(
      TripRootPatch rootPatch, TripAggregateMutationEffect effect, T payload) {
    return invalidate(rootPatch, TripAggregateMutationEffect.NONE, effect, payload);
  }

  public static <T> TripAggregateMutationPlan<T> invalidate(
      TripRootPatch rootPatch,
      TripAggregateMutationEffect beforeRootEffect,
      TripAggregateMutationEffect effect,
      T payload) {
    return new TripAggregateMutationPlan<>(
        rootPatch, TripScheduleEffect.INVALIDATE, beforeRootEffect, effect, payload);
  }
}
