package com.timingjeju.api.application.mobility;

public interface MobilityRouteProvider {
  String sourceId();

  MobilityRouteMeasurement fetch(MobilityRouteRequest request);
}
