package com.timingjeju.api.application.demo;

import java.util.UUID;

public record DemoPlaceRow(
    UUID id,
    UUID importRunId,
    String contentId,
    String contentTypeId,
    String name,
    String category,
    String address,
    String overview,
    String imageUrl,
    String thumbnailUrl,
    Double longitude,
    Double latitude) {}
