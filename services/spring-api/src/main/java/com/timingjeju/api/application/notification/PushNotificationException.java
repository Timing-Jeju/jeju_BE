package com.timingjeju.api.application.notification;

public final class PushNotificationException extends RuntimeException {

  private final String code;

  private PushNotificationException(String code) {
    super(null, null, false, false);
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static PushNotificationException invalidRequest() {
    return new PushNotificationException("INVALID_PUSH_NOTIFICATION_REQUEST");
  }

  public static PushNotificationException dataUnavailable() {
    return new PushNotificationException("PUSH_NOTIFICATION_DATA_UNAVAILABLE");
  }
}
