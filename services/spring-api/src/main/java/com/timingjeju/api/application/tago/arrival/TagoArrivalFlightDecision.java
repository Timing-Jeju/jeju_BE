package com.timingjeju.api.application.tago.arrival;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record TagoArrivalFlightDecision(
    TagoArrivalFlightStatus status,
    TagoArrivalFlightLease lease,
    TagoArrivalException.Code rawOutcome) {
  public TagoArrivalFlightDecision {
    Objects.requireNonNull(status, "flight status는 필수입니다.");
    Objects.requireNonNull(lease, "flight lease는 필수입니다.");
    if ((status == TagoArrivalFlightStatus.FAILED || status == TagoArrivalFlightStatus.ABANDONED)
        != (rawOutcome != null)) {
      throw new IllegalArgumentException("flight outcome 조합이 올바르지 않습니다.");
    }
  }

  public Optional<TagoArrivalException.Code> outcome() {
    return Optional.ofNullable(rawOutcome);
  }

  public static TagoArrivalFlightDecision leader(
      String fingerprint, long generation, UUID ownerToken) {
    return new TagoArrivalFlightDecision(
        TagoArrivalFlightStatus.LEADER,
        new TagoArrivalFlightLease(fingerprint, generation, ownerToken),
        null);
  }

  public static TagoArrivalFlightDecision running(TagoArrivalFlightLease lease) {
    return new TagoArrivalFlightDecision(TagoArrivalFlightStatus.RUNNING, lease, null);
  }

  public static TagoArrivalFlightDecision succeeded(TagoArrivalFlightLease lease) {
    return new TagoArrivalFlightDecision(TagoArrivalFlightStatus.SUCCEEDED, lease, null);
  }

  public static TagoArrivalFlightDecision failed(
      TagoArrivalFlightLease lease, TagoArrivalException.Code outcome) {
    return new TagoArrivalFlightDecision(TagoArrivalFlightStatus.FAILED, lease, outcome);
  }

  public static TagoArrivalFlightDecision abandoned(TagoArrivalFlightLease lease) {
    return new TagoArrivalFlightDecision(
        TagoArrivalFlightStatus.ABANDONED, lease, TagoArrivalException.Code.DATA_UNAVAILABLE);
  }
}
