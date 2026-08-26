package com.timingjeju.api.global.push.firebase;

import java.time.Duration;

record FirebaseCallFailure(
    FirebaseFailureKind kind, Integer httpStatus, String providerErrorCode, Duration retryAfter) {

  static FirebaseCallFailure providerResponse(
      int httpStatus, String providerErrorCode, Duration retryAfter) {
    return new FirebaseCallFailure(
        FirebaseFailureKind.PROVIDER_RESPONSE, httpStatus, providerErrorCode, retryAfter);
  }

  static FirebaseCallFailure provenPreConnect() {
    return new FirebaseCallFailure(FirebaseFailureKind.PROVEN_PRE_CONNECT, null, null, null);
  }

  static FirebaseCallFailure postWriteAmbiguous(String sanitizedReason) {
    return new FirebaseCallFailure(
        FirebaseFailureKind.POST_WRITE_AMBIGUOUS, null, sanitizedReason, null);
  }
}
