package com.timingjeju.api.application.trip;

import java.time.LocalDate;
import java.util.UUID;

public record TripDay(UUID dayId, int dayNo, LocalDate date) {}
