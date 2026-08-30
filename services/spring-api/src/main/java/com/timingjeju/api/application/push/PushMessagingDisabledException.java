package com.timingjeju.api.application.push;

public final class PushMessagingDisabledException extends IllegalStateException {
  public PushMessagingDisabledException() {
    super("FCM 발송 기능이 비활성화되어 있습니다.");
  }
}
