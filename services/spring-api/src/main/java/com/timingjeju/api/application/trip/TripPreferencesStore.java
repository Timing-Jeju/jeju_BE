package com.timingjeju.api.application.trip;

public interface TripPreferencesStore {
  TripPreferencesMutation replaceOwned(TripPreferencesUpdate update);
}
