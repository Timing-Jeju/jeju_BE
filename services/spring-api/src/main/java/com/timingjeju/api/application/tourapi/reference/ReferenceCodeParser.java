package com.timingjeju.api.application.tourapi.reference;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.util.List;

public interface ReferenceCodeParser {
  List<ReferenceCode> parse(
      ReferenceCodeOperation operation, SnapshotPayloadFormat format, byte[] payload);
}
