package com.timingjeju.api.application.tourapi.detail;

public interface PlaceDetailRepository {
  PlaceDetailUpsertResult upsert(PlaceDetailUpsertCommand command);
}
