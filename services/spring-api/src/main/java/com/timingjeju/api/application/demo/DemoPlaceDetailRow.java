package com.timingjeju.api.application.demo;

import java.util.UUID;

public record DemoPlaceDetailRow(
    UUID placeId,
    UUID importRunId,
    String phone,
    String operatingHoursText,
    String closedDaysText,
    String parkingText,
    String introAttributes,
    UUID sourceSnapshotId) {}
