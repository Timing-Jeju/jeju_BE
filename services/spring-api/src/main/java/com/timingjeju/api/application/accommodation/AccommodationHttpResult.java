package com.timingjeju.api.application.accommodation;

import java.util.Objects;

public record AccommodationHttpResult(AccommodationHttpSnapshot snapshot, boolean replayed) {
  public AccommodationHttpResult {
    Objects.requireNonNull(snapshot);
  }
}
