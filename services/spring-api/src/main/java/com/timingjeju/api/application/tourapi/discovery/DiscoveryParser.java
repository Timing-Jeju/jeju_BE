package com.timingjeju.api.application.tourapi.discovery;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import com.timingjeju.api.application.tourapi.place.PlaceListPage;

public interface DiscoveryParser {
  PlaceListPage parse(DiscoveryOperation operation, SnapshotPayloadFormat format, byte[] payload);
}
