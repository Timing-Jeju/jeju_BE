package com.timingjeju.api.global.push.firebase;

import java.util.Objects;

record FirebaseCallResult(String providerMessageId, FirebaseCallFailure failure) {
  FirebaseCallResult {
    if ((providerMessageId == null) == (failure == null)) {
      throw new IllegalArgumentException("Firebase 호출 결과는 접수 또는 실패 중 하나여야 합니다.");
    }
  }

  static FirebaseCallResult accepted(String providerMessageId) {
    return new FirebaseCallResult(Objects.requireNonNull(providerMessageId), null);
  }

  static FirebaseCallResult failed(FirebaseCallFailure failure) {
    return new FirebaseCallResult(null, Objects.requireNonNull(failure));
  }

  boolean accepted() {
    return providerMessageId != null;
  }
}
