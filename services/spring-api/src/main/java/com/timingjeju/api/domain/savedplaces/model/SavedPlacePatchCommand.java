package com.timingjeju.api.domain.savedplaces.model;

public record SavedPlacePatchCommand(
    PresentValue<String> memo,
    PresentValue<java.util.List<String>> tags,
    PresentValue<Integer> priority,
    PresentValue<Integer> targetDay) {}
