package com.timingjeju.api.application.mobility;

@FunctionalInterface
public interface ConservativeWalkEstimator {
  MobilityRouteMeasurement estimate(MobilityRouteRequest request);
}
