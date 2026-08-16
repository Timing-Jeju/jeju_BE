package com.timingjeju.api.application.tago.stop;

public record TagoStation(
    String cityCode,
    String nodeId,
    String nodeNo,
    String nodeName,
    double longitude,
    double latitude) {}
