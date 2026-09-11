package com.timingjeju.api.application.timetable;

import java.time.LocalTime;
import java.util.UUID;

public record TimetableEntryCandidate(
    UUID routeId,
    UUID stopId,
    String directionKey,
    String serviceDayType,
    LocalTime departureTime,
    String sourceRecordKey,
    String sourceProvider,
    String sourceService,
    String routeSourceProvider,
    String routeCityCode) {}
