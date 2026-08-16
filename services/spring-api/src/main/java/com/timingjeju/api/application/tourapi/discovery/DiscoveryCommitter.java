package com.timingjeju.api.application.tourapi.discovery;

import com.timingjeju.api.application.tourapi.place.PlaceListUpsertResult;

public interface DiscoveryCommitter {
  PlaceListUpsertResult commit(DiscoveryCommitCommand command);
}
