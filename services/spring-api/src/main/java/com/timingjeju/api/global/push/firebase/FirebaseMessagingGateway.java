package com.timingjeju.api.global.push.firebase;

import com.google.firebase.messaging.Message;

@FunctionalInterface
interface FirebaseMessagingGateway {
  FirebaseCallResult send(Message message);
}
