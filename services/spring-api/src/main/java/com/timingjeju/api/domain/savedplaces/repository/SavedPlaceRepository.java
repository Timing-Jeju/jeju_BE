package com.timingjeju.api.domain.savedplaces.repository;

import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCommand;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCreateResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceHttpSnapshot;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacePatchCommand;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceUpdateResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesListResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesQuery;
import java.util.UUID;

public interface SavedPlaceRepository {
  SavedPlacesListResult list(UUID owner, SavedPlacesQuery query);

  SavedPlaceCreateResult create(UUID owner, String key, SavedPlaceCommand command);

  void completeSnapshot(UUID owner, String key, SavedPlaceHttpSnapshot snapshot);

  SavedPlaceUpdateResult patch(
      UUID owner, UUID placeId, String ifMatch, SavedPlacePatchCommand command);

  boolean delete(UUID owner, UUID placeId);
}
