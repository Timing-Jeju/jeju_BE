package com.timingjeju.api.application.demo;

import java.util.UUID;

public record DemoPlaceDetailItemRow(
    UUID id,
    UUID placeId,
    String contentTypeId,
    String itemType,
    String sourceItemKey,
    Integer sequenceNo,
    String title,
    UUID importRunId) {}
