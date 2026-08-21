package com.timingjeju.api.application.tourapi.discovery;

import com.timingjeju.api.application.tourapi.place.PlaceListSourceResponse;

public interface DiscoverySource {
  PlaceListSourceResponse fetch(DiscoveryImportCommand command, int pageNo);
}
