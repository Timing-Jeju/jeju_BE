package com.timingjeju.api.application.demo;

import java.util.UUID;

public record DemoPlaceImageRow(
    UUID id,
    UUID placeId,
    String imageUrl,
    String thumbnailUrl,
    UUID importRunId,
    String sourceImageId) {}
