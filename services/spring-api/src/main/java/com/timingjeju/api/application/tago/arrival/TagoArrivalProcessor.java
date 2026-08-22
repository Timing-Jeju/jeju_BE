package com.timingjeju.api.application.tago.arrival;

import java.time.Instant;

public interface TagoArrivalProcessor {
  TagoArrivalProcessResult process(
      TagoArrivalFlightLease flight,
      TagoArrivalCacheKey key,
      TagoArrivalSourceResponse response,
      Instant observedAt,
      Instant expiresAt);

  TagoArrivalException.Code recordTransportFailure(
      TagoArrivalFlightLease flight,
      TagoArrivalCacheKey key,
      Instant observedAt,
      TagoArrivalException.Code code);
}
