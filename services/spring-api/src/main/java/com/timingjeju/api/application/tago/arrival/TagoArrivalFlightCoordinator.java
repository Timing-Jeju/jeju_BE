package com.timingjeju.api.application.tago.arrival;

import java.util.function.Function;
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

  default TagoArrivalSnapshot coalesce(
      TagoArrivalCacheKey key,
      Function<TagoArrivalFlightLease, TagoArrivalSnapshot> leaderAction,
      Supplier<TagoArrivalSnapshot> replayAction) {
    TagoArrivalFlightLease localLease =
        new TagoArrivalFlightLease("0".repeat(64), 1, new java.util.UUID(0, 0));
    return coalesce(key, () -> leaderAction.apply(localLease), replayAction);
  }
}
