package com.timingjeju.api.application.tago.arrival;

import java.util.Optional;

@FunctionalInterface
public interface TagoArrivalHistory {
  Optional<TagoArrivalSnapshot> findLatest(TagoArrivalCacheKey key);
}
