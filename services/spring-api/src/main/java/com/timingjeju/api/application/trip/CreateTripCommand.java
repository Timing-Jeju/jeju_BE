package com.timingjeju.api.application.trip;

import java.time.LocalDate;
import java.util.List;

public record CreateTripCommand(
    String title,
    LocalDate startDate,
    LocalDate endDate,
    String timezone,
    String userPace,
    List<TripTransportMode> transportModes) {
  public CreateTripCommand {
    transportModes = List.copyOf(transportModes);
  }
}
