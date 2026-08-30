package com.timingjeju.api.application.notification;

import java.time.Instant;

public record NotificationPreference(
    boolean nextDestinationDepartureEnabled, int safetyBufferMinutes, Instant updatedAt) {}
