package com.timingjeju.api.application.accommodation;

import java.util.UUID;

public interface AccommodationStore {
  AccommodationCreateStoreResult create(AccommodationCreateRecord record);

  void completeCreateSnapshot(
      UUID ownerId, UUID tripId, String key, AccommodationHttpSnapshot snapshot);

  AccommodationMutation patch(AccommodationPatchRecord record);

  void delete(AccommodationDeleteRecord record);
}
