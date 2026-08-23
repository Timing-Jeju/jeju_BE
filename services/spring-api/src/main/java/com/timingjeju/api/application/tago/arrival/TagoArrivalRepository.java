package com.timingjeju.api.application.tago.arrival;

import java.util.Optional;

public interface TagoArrivalRepository extends TagoArrivalHistory {
  int append(TagoArrivalCommitCommand command);

  Optional<TagoArrivalSnapshot> findLatest(TagoArrivalCacheKey key);
}
