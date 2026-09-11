package com.timingjeju.api.global.profile;

import com.timingjeju.api.application.profile.ProfileImageException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class JdkProfileImageStorageHttpTransport implements ProfileImageStorageHttpTransport {

  private final HttpClient client;

  JdkProfileImageStorageHttpTransport(ProfileImageStorageSettings settings) {
    client =
        HttpClient.newBuilder()
            .connectTimeout(settings.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public ProfileImageStorageHttpResponse exchange(
      HttpRequest request, byte[] requestBody, int maximumBodyBytes) {
    try {
      HttpResponse<InputStream> response =
          client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        byte[] bounded = body.readNBytes(maximumBodyBytes + 1);
        if (bounded.length > maximumBodyBytes) {
          throw ProfileImageException.storageUnavailable();
        }
        return new ProfileImageStorageHttpResponse(
            response.statusCode(), response.headers().map(), bounded);
      }
    } catch (ProfileImageException failure) {
      throw failure;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw ProfileImageException.storageUnavailable();
    } catch (Exception failure) {
      throw ProfileImageException.storageUnavailable();
    }
  }
}
