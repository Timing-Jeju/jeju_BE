package com.timingjeju.api.global.push.firebase;

import com.timingjeju.api.application.push.PushMessage;
import com.timingjeju.api.application.push.PushMessageSender;
import com.timingjeju.api.application.push.PushMessagingDisabledException;
import com.timingjeju.api.application.push.PushSendResult;

final class DisabledPushMessageSender implements PushMessageSender {
  @Override
  public PushSendResult send(PushMessage message) {
    throw new PushMessagingDisabledException();
  }
}
