package com.timingjeju.api.global.push.firebase;

import java.time.Clock;

@FunctionalInterface
interface FirebaseAdminClientFactory {
  FirebaseMessagingGateway create(FcmClientSettings settings, Clock clock);
}
