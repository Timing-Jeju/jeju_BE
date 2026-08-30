package com.timingjeju.api.application.notification;

import java.time.Instant;
import java.util.UUID;

public record PushDevice(
    UUID deviceId,
    PushPlatform platform,
    PushPermissionStatus permissionStatus,
    boolean active,
    Instant updatedAt) {}
