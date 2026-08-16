package com.timingjeju.api.application.tourapi.image;

@FunctionalInterface
public interface PlaceImageRepository {
  PlaceImageSyncResult sync(PlaceImageSyncCommand command);
}
