package com.timingjeju.api.application.tourapi.detailitem;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;

public interface DetailInfoParser {
  DetailItemPage parse(
      SnapshotPayloadFormat format, byte[] payload, String contentId, String contentTypeId);
}
