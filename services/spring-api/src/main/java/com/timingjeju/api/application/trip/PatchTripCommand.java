package com.timingjeju.api.application.trip;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record PatchTripCommand(
    TripPatchValue<String> title,
    TripPatchValue<LocalDate> startDate,
    TripPatchValue<LocalDate> endDate,
    TripPatchValue<String> timezone,
    TripPatchValue<String> userPace,
    TripPatchValue<List<TripTransportMode>> transportModes) {
  public PatchTripCommand {
    Objects.requireNonNull(title);
    Objects.requireNonNull(startDate);
    Objects.requireNonNull(endDate);
    Objects.requireNonNull(timezone);
    Objects.requireNonNull(userPace);
    Objects.requireNonNull(transportModes);
    if (transportModes.present()) {
      transportModes = TripPatchValue.present(List.copyOf(transportModes.value()));
    }
  }

  public static PatchTripCommand empty() {
    return new PatchTripCommand(
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.omitted(),
        TripPatchValue.omitted());
  }

  public boolean emptyPatch() {
    return !title.present()
        && !startDate.present()
        && !endDate.present()
        && !timezone.present()
        && !userPace.present()
        && !transportModes.present();
  }
}
