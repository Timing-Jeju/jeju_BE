package com.timingjeju.api.application.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferenceStore {

  Optional<NotificationPreference> find(UUID userId);

  NotificationPreference save(UUID userId, NotificationPreferenceUpdate update, Instant updatedAt);
}
