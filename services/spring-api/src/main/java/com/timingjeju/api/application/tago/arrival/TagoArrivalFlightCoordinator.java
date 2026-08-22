package com.timingjeju.api.application.tago.arrival;

import java.util.function.Supplier;

@FunctionalInterface
public interface TagoArrivalFlightCoordinator {
  TagoArrivalSnapshot coalesce(
      TagoArrivalCacheKey key, Supplier<TagoArrivalSnapshot> coordinatedAction);

  default TagoArrivalSnapshot coalesce(
      TagoArrivalCacheKey key,
      Supplier<TagoArrivalSnapshot> leaderAction,
      Supplier<TagoArrivalSnapshot> replayAction) {
    return coalesce(key, leaderAction);
  }
}
