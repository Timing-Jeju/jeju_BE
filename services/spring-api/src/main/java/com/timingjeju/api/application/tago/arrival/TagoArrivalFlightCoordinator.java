package com.timingjeju.api.application.tago.arrival;

import java.util.function.Supplier;

@FunctionalInterface
public interface TagoArrivalFlightCoordinator {
  TagoArrivalSnapshot coalesce(
      TagoArrivalCacheKey key, Supplier<TagoArrivalSnapshot> coordinatedAction);
}
