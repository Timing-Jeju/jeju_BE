package com.timingjeju.api.application.tourapi.detail;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;

public interface DetailCommonParser {
  PlaceDetailCommon parse(SnapshotPayloadFormat format, byte[] payload);
}
