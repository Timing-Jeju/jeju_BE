package com.timingjeju.api.application.tourapi.place;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;

public interface PlaceListParser {
  PlaceListPage parse(SnapshotPayloadFormat format, byte[] payload);
}
