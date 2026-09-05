package com.timingjeju.api.application.accommodation;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record CreateAccommodationCommand(
    UUID placeId,
    String customName,
    LocalDate checkInDate,
    LocalDate checkOutDate,
    LocalTime checkInTime,
    LocalTime checkOutTime) {}
