package com.timingjeju.api.domain.weather.model;

/** 공개 tour_places 좌표만 담으며 사용자 현재 위치나 facts JSON을 받지 않는다. */
public record PublicWeatherAnchor(double latitude, double longitude) {}
