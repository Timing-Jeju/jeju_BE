package com.timingjeju.api.application.tourapi.detail;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;

public interface DetailIntroParser {
  PlaceDetailIntro parse(SnapshotPayloadFormat format, byte[] payload);
}
