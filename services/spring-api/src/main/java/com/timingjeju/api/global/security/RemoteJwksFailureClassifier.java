package com.timingjeju.api.global.security;

import com.nimbusds.jose.RemoteKeySourceException;
import java.io.IOException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

final class RemoteJwksFailureClassifier {

  private RemoteJwksFailureClassifier() {}

  static boolean isAvailabilityFailure(Throwable throwable) {
    RemoteKeySourceException remoteFailure = findRemoteFailure(throwable);
    if (remoteFailure == null) {
      return false;
    }
    Throwable cause = remoteFailure.getCause();
    return cause instanceof IOException
        || cause instanceof ResourceAccessException
        || cause instanceof HttpServerErrorException;
  }

  private static RemoteKeySourceException findRemoteFailure(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof RemoteKeySourceException remoteFailure) {
        return remoteFailure;
      }
      current = current.getCause();
    }
    return null;
  }
}
