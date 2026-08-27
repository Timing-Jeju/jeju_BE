package com.timingjeju.api.domain.savedplaces.model;

public record SavedPlaceCreateResult(
    SavedPlace place,
    String etag,
    boolean replayed,
    boolean created,
    SavedPlaceHttpSnapshot snapshot) {
  public SavedPlaceCreateResult(SavedPlace place, String etag, boolean replayed, boolean created) {
    this(place, etag, replayed, created, null);
  }

  public SavedPlaceCreateResult withSnapshot(SavedPlaceHttpSnapshot value) {
    return new SavedPlaceCreateResult(place, etag, replayed, created, value);
  }
}
