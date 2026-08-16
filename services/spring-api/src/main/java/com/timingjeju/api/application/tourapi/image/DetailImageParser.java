package com.timingjeju.api.application.tourapi.image;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;

@FunctionalInterface
public interface DetailImageParser {
  PlaceImagePage parse(SnapshotPayloadFormat format, byte[] payload, String contentId);
}
