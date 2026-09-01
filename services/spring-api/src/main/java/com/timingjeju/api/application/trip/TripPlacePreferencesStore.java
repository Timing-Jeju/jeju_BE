package com.timingjeju.api.application.trip;

public interface TripPlacePreferencesStore {
  TripPlacePreferencesMutation replaceOwned(TripPlacePreferencesUpdate update);
}
