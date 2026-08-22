package com.timingjeju.api.application.tago.arrival;

import java.time.Duration;
import java.util.UUID;

public interface TagoArrivalFlightStore {
  TagoArrivalFlightDecision observeOrClaim(
      String fingerprint, UUID proposedOwner, Duration lease, Duration quarantine);

  boolean completeSuccess(TagoArrivalFlightLease lease, Duration retain);

  boolean completeFailure(
      TagoArrivalFlightLease lease, TagoArrivalException.Code code, Duration retain);

  boolean abandon(TagoArrivalFlightLease lease, Duration quarantine);
}
