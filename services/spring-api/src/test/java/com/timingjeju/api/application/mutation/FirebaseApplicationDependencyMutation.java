package com.timingjeju.api.application.mutation;

import com.google.firebase.messaging.FirebaseMessaging;

public final class FirebaseApplicationDependencyMutation {
  private FirebaseMessaging forbiddenDependency;

  public FirebaseMessaging forbiddenDependency() {
    return forbiddenDependency;
  }
}
