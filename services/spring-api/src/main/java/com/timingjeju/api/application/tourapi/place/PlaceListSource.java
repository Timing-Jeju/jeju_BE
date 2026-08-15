package com.timingjeju.api.application.tourapi.place;

public interface PlaceListSource {
  PlaceListSourceResponse fetch(int pageNo);
}
