package com.timingjeju.api.global.push.firebase;

import com.timingjeju.api.application.push.PushErrorClass;
import com.timingjeju.api.application.push.PushSendResult;

final class FirebaseErrorClassifier {

  PushSendResult classify(FirebaseCallFailure failure) {
    if (failure.kind() == FirebaseFailureKind.PROVEN_PRE_CONNECT) {
      return new PushSendResult.RetryableFailure(PushErrorClass.PRE_CONNECT, null);
    }
    if (failure.kind() == FirebaseFailureKind.POST_WRITE_AMBIGUOUS) {
      return new PushSendResult.AcceptanceUnknown(PushErrorClass.POST_WRITE_AMBIGUOUS);
    }

    String code = failure.providerErrorCode();
    int status = failure.httpStatus() == null ? 0 : failure.httpStatus();
    if (status == 429 || "QUOTA_EXCEEDED".equals(code)) {
      return new PushSendResult.RetryableFailure(PushErrorClass.RATE_LIMITED, failure.retryAfter());
    }
    if (status >= 500 && status <= 599) {
      return new PushSendResult.RetryableFailure(PushErrorClass.SERVER_ERROR, failure.retryAfter());
    }
    if ("UNREGISTERED".equals(code)) {
      return new PushSendResult.PermanentFailure(PushErrorClass.TOKEN_UNREGISTERED, true);
    }
    if ("INVALID_ARGUMENT".equals(code)) {
      // The mapper has already proved the closed payload contract. Firebase documents this
      // remaining INVALID_ARGUMENT response as an invalid registration token signal.
      return new PushSendResult.PermanentFailure(PushErrorClass.TOKEN_INVALID, true);
    }
    if (status == 401 || status == 403 || "THIRD_PARTY_AUTH_ERROR".equals(code)) {
      return new PushSendResult.PermanentFailure(PushErrorClass.CREDENTIAL, false);
    }
    if ("SENDER_ID_MISMATCH".equals(code)) {
      return new PushSendResult.PermanentFailure(PushErrorClass.CONFIGURATION, false);
    }
    return new PushSendResult.PermanentFailure(PushErrorClass.PERMANENT_PROVIDER_ERROR, false);
  }
}
