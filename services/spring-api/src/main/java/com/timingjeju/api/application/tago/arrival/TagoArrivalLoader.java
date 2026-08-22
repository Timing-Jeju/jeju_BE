package com.timingjeju.api.application.tago.arrival;

@FunctionalInterface
public interface TagoArrivalLoader {
  TagoArrivalSnapshot load(TagoArrivalCacheKey key);

  default TagoArrivalSnapshot load(TagoArrivalCacheKey key, TagoArrivalFlightLease flight) {
    return load(key);
  }
}
