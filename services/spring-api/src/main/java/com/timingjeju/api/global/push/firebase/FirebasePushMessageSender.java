package com.timingjeju.api.global.push.firebase;

import com.timingjeju.api.application.push.PushMessage;
import com.timingjeju.api.application.push.PushMessageSender;
import com.timingjeju.api.application.push.PushSendResult;
import java.time.Instant;

public final class FirebasePushMessageSender implements PushMessageSender {
  private final FirebaseMessagingGateway gateway;
  private final FirebaseMessageMapper mapper;
  private final FirebaseErrorClassifier classifier;
  private final FirebasePushTelemetry telemetry;

  FirebasePushMessageSender(
      FirebaseMessagingGateway gateway,
      FirebaseMessageMapper mapper,
      FirebaseErrorClassifier classifier,
      FirebasePushTelemetry telemetry) {
    this.gateway = gateway;
    this.mapper = mapper;
    this.classifier = classifier;
    this.telemetry = telemetry;
  }

  @Override
  public PushSendResult send(PushMessage message) {
    Instant startedAt = telemetry.start();
    FirebaseCallResult call = gateway.send(mapper.map(message));
    PushSendResult result =
        call.accepted()
            ? new PushSendResult.Accepted(call.providerMessageId())
            : classifier.classify(call.failure());
    telemetry.record(result, startedAt);
    return result;
  }
}
