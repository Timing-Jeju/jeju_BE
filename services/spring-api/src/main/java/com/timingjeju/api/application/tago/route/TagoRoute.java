package com.timingjeju.api.application.tago.route;

public record TagoRoute(
    String cityCode,
    String externalRouteId,
    String routeNo,
    String routeType,
    String startNodeName,
    String endNodeName,
    String directionKey) {}
