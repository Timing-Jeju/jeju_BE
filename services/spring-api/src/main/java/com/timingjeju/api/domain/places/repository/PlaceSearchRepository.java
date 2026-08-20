package com.timingjeju.api.domain.places.repository;

import com.timingjeju.api.domain.places.dto.request.PlacesListQuery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlaceSearchRepository {
  List<PlaceSearchRow> search(
      PlacesListQuery query, PlaceSearchPosition after, Optional<UUID> currentUserId);
}
