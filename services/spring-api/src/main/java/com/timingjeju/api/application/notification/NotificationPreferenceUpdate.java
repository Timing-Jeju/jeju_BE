package com.timingjeju.api.application.notification;

public record NotificationPreferenceUpdate(
    boolean nextDestinationDepartureEnabled, int safetyBufferMinutes) {}
