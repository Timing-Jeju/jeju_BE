package com.timingjeju.api.application.tago.arrival;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.util.List;

public interface TagoArrivalPayloadParser {
  List<TagoArrival> parse(SnapshotPayloadFormat format, byte[] payload);
}
