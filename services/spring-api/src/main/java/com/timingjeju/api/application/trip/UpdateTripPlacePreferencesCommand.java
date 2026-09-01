package com.timingjeju.api.application.trip;

import java.util.List;

public record UpdateTripPlacePreferencesCommand(List<TripPlacePreference> items) {}
