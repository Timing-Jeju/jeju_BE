package com.timingjeju.api.application.tourapi.place;

public interface PlaceListRepository {
  PlaceListUpsertResult upsert(PlaceListUpsertCommand command);
}
