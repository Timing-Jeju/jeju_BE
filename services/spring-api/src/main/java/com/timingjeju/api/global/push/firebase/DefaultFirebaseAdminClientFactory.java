package com.timingjeju.api.global.push.firebase;

import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseOptions;
import java.io.IOException;
import java.time.Clock;
import java.util.List;

final class DefaultFirebaseAdminClientFactory implements FirebaseAdminClientFactory {
  @Override
  public FirebaseMessagingGateway create(FcmClientSettings settings, Clock clock) {
    try {
      GoogleCredentials credentials =
          GoogleCredentials.getApplicationDefault()
              .createScoped(List.of("https://www.googleapis.com/auth/firebase.messaging"));
      // FCM is explicitly enabled here, so startup verifies the mounted/ADC credential before
      // serving.
      credentials.refreshAccessToken();
      FirebaseOptions options =
          FirebaseOptions.builder()
              .setCredentials(credentials)
              .setProjectId(settings.projectId())
              .setConnectTimeout(settings.connectTimeoutMillis())
              .setReadTimeout(settings.readTimeoutMillis())
              .setWriteTimeout(settings.writeTimeoutMillis())
              .build();
      return new FirebaseAdminMessagingGateway(
          options.getHttpTransport().createRequestFactory(new HttpCredentialsAdapter(credentials)),
          options.getJsonFactory(),
          settings.projectId(),
          settings.connectTimeoutMillis(),
          settings.readTimeoutMillis(),
          settings.writeTimeoutMillis(),
          clock);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("FCM ADC 또는 secret mount 자격 증명을 초기화할 수 없습니다.");
    }
  }
}
