package com.timingjeju.api.application.tago.route;

import com.timingjeju.api.application.snapshot.SnapshotPayloadFormat;

public interface TagoRoutePayloadParser {
  TagoRoutePage parseRouteList(
      SnapshotPayloadFormat format, byte[] payload, String cityCode, String routeNo, int pageNo);

  TagoRoute parseRouteDetail(
      SnapshotPayloadFormat format, byte[] payload, String cityCode, String routeId);

  TagoRouteStopPage parseRouteStops(
      SnapshotPayloadFormat format, byte[] payload, String cityCode, String routeId, int pageNo);
}
