package com.timingjeju.api.application.notification.service;

import com.timingjeju.api.application.notification.NotificationPreference;
import com.timingjeju.api.application.notification.NotificationPreferencePatch;
import com.timingjeju.api.application.notification.NotificationPreferenceStore;
import com.timingjeju.api.application.notification.NotificationPreferenceUpdate;
import com.timingjeju.api.application.notification.PushNotificationException;
import com.timingjeju.api.application.security.CurrentUser;
import java.time.Clock;
import java.util.Objects;

public final class NotificationPreferenceService {

  private static final NotificationPreference DEFAULT = new NotificationPreference(false, 10, null);
  private final NotificationPreferenceStore preferences;
  private final Clock clock;

  public NotificationPreferenceService(NotificationPreferenceStore preferences, Clock clock) {
    this.preferences = Objects.requireNonNull(preferences);
    this.clock = Objects.requireNonNull(clock);
  }

  public NotificationPreference read(CurrentUser user) {
    return preferences.find(user.userId()).orElse(DEFAULT);
  }

  public NotificationPreference update(CurrentUser user, NotificationPreferencePatch patch) {
    if (patch == null || (!patch.enabledPresent() && !patch.safetyBufferPresent())) {
      throw PushNotificationException.invalidRequest();
    }
    NotificationPreference current = preferences.find(user.userId()).orElse(DEFAULT);
    boolean enabled =
        patch.enabledPresent()
            ? required(patch.nextDestinationDepartureEnabled())
            : current.nextDestinationDepartureEnabled();
    int safety =
        patch.safetyBufferPresent()
            ? required(patch.safetyBufferMinutes())
            : current.safetyBufferMinutes();
    if (safety < 0 || safety > 120) {
      throw PushNotificationException.invalidRequest();
    }
    return preferences.save(
        user.userId(), new NotificationPreferenceUpdate(enabled, safety), clock.instant());
  }

  private static boolean required(Boolean value) {
    if (value == null) {
      throw PushNotificationException.invalidRequest();
    }
    return value;
  }

  private static int required(Integer value) {
    if (value == null) {
      throw PushNotificationException.invalidRequest();
    }
    return value;
  }
}
