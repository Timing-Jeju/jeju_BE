package com.timingjeju.api.application.tago.route;

public record TagoRouteStop(
    String cityCode, String externalRouteId, String nodeId, int stopSequence) {}
