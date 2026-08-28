package com.timingjeju.api.application.push;

public interface PushMessageSender {
  PushSendResult send(PushMessage message);
}
