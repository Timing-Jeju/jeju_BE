package com.timingjeju.api.application.notification;

public record NotificationPreferencePatch(
    boolean enabledPresent,
    Boolean nextDestinationDepartureEnabled,
    boolean safetyBufferPresent,
    Integer safetyBufferMinutes) {}
