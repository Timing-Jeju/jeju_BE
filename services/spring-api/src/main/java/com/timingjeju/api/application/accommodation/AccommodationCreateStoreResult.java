package com.timingjeju.api.application.accommodation;

public record AccommodationCreateStoreResult(
    AccommodationMutation mutation, AccommodationHttpSnapshot replaySnapshot) {
  public AccommodationCreateStoreResult {
    if ((mutation == null) == (replaySnapshot == null)) {
      throw new IllegalArgumentException("신규 mutation 또는 replay snapshot 하나만 필요합니다.");
    }
  }

  public static AccommodationCreateStoreResult created(AccommodationMutation mutation) {
    return new AccommodationCreateStoreResult(mutation, null);
  }

  public static AccommodationCreateStoreResult replayed(AccommodationHttpSnapshot snapshot) {
    return new AccommodationCreateStoreResult(null, snapshot);
  }
}
