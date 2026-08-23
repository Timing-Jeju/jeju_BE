package com.timingjeju.api.application.tago.arrival;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public interface TagoArrivalFlightStore {
  TagoArrivalFlightDecision observeOrClaim(
      String fingerprint, UUID proposedOwner, Duration lease, Duration quarantine);

  boolean completeSuccess(TagoArrivalFlightLease lease, Duration retain);

  default boolean completeSuccess(
      TagoArrivalFlightLease lease, Instant sourceExpiresAt, Duration retain) {
    return completeSuccess(lease, retain);
  }

  boolean completeFailure(
      TagoArrivalFlightLease lease, TagoArrivalException.Code code, Duration retain);

  boolean abandon(TagoArrivalFlightLease lease, Duration quarantine);

  default void lockCurrent(TagoArrivalFlightLease lease) {
    throw TagoArrivalException.dataUnavailable();
  }

  default int cleanupExpiredTerminals(String currentFingerprint, int limit) {
    return 0;
  }
}
