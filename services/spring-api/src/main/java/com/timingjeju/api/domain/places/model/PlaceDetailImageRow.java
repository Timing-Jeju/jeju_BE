package com.timingjeju.api.domain.places.model;

import java.time.Instant;
import java.util.UUID;

public record PlaceDetailImageRow(
    UUID id,
    String url,
    String thumbnailUrl,
    String provider,
    Instant observedAt,
    Instant expiresAt,
    boolean stale) {}
