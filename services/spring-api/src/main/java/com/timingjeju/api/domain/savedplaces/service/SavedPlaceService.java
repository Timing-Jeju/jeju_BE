package com.timingjeju.api.domain.savedplaces.service;

import com.timingjeju.api.domain.savedplaces.dto.SavedPlaceResponse;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCommand;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceCreateResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceHttpSnapshot;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacePatchCommand;
import com.timingjeju.api.domain.savedplaces.model.SavedPlaceUpdateResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesListResult;
import com.timingjeju.api.domain.savedplaces.model.SavedPlacesQuery;
import com.timingjeju.api.domain.savedplaces.repository.SavedPlaceRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SavedPlaceService {
  private final SavedPlaceRepository repository;
  private final ObjectMapper objectMapper;

  public SavedPlaceService(SavedPlaceRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public SavedPlacesListResult list(UUID owner, SavedPlacesQuery query) {
    return repository.list(owner, query);
  }

  @Transactional
  public SavedPlaceCreateResult create(UUID owner, String key, SavedPlaceCommand command) {
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
      throw com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException.invalidRequest();
    }
    SavedPlaceCreateResult result = repository.create(owner, key, command);
    if (result.snapshot() != null) return result;
    String location = "/api/v1/me/saved-places/" + result.place().placeId();
    SavedPlaceHttpSnapshot snapshot =
        new SavedPlaceHttpSnapshot(
            result.created() ? 201 : 200,
            "application/json",
            location,
            result.etag(),
            objectMapper.writeValueAsBytes(SavedPlaceResponse.from(result.place())));
    repository.completeSnapshot(owner, key, snapshot);
    return result.withSnapshot(snapshot);
  }

  @Transactional
  public SavedPlaceUpdateResult patch(
      UUID owner, UUID placeId, String ifMatch, SavedPlacePatchCommand command) {
    if (ifMatch == null || !ifMatch.matches("\"[A-Za-z0-9._:-]{1,128}\"")) {
      throw com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException.invalidRequest();
    }
    return repository.patch(owner, placeId, ifMatch, command);
  }

  @Transactional
  public void delete(UUID owner, UUID placeId) {
    if (!repository.delete(owner, placeId)) {
      throw com.timingjeju.api.domain.savedplaces.dto.SavedPlaceException.of(
          "SAVED_PLACE_NOT_FOUND");
    }
  }
}
