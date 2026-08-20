package com.timingjeju.api.domain.places.repository;

import java.util.UUID;

public record PlaceSearchPosition(Long distanceMeters, String normalizedName, UUID placeId) {}
