package com.timingjeju.api.domain.places.repository;

import com.timingjeju.api.domain.places.model.PlaceDetailSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface PlaceDetailRepository {
  Optional<PlaceDetailSnapshot> find(UUID placeId, Optional<UUID> currentUserId);
}
