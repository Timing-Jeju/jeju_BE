package com.timingjeju.api.application.tago.stop;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;
import java.util.List;

public interface TagoStopPayloadParser {
  List<TagoCityCode> parseCityCodes(SnapshotPayloadFormat format, byte[] payload);

  String discoverJejuCityCode(SnapshotPayloadFormat format, byte[] payload);

  TagoStationPage parseStations(
      SnapshotPayloadFormat format, byte[] payload, String expectedCityCode, int expectedPageNo);
}
