package com.timingjeju.api.application.push;

import java.util.Objects;

public record PushPlatformHints(
    AndroidPriority androidPriority, ApnsPresentation apnsPresentation) {

  public PushPlatformHints {
    Objects.requireNonNull(androidPriority, "androidPriority");
    Objects.requireNonNull(apnsPresentation, "apnsPresentation");
  }

  public static PushPlatformHints visibleTimeSensitive() {
    return new PushPlatformHints(AndroidPriority.HIGH, ApnsPresentation.ALERT_WITH_SOUND);
  }

  public enum AndroidPriority {
    NORMAL,
    HIGH
  }

  public enum ApnsPresentation {
    ALERT,
    ALERT_WITH_SOUND
  }
}
