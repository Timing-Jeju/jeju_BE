package com.timingjeju.api.global.push.firebase;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.json.JsonHttpContent;
import com.google.api.client.json.JsonFactory;
import com.google.firebase.messaging.Message;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;

final class FirebaseAdminMessagingGateway implements FirebaseMessagingGateway {
  private static final String FCM_ENDPOINT =
      "https://fcm.googleapis.com/v1/projects/%s/messages:send";

  private final HttpRequestFactory requestFactory;
  private final JsonFactory jsonFactory;
  private final GenericUrl endpoint;
  private final int connectTimeoutMillis;
  private final int readTimeoutMillis;
  private final int writeTimeoutMillis;
  private final Clock clock;

  FirebaseAdminMessagingGateway(
      HttpRequestFactory requestFactory,
      JsonFactory jsonFactory,
      String projectId,
      int connectTimeoutMillis,
      int readTimeoutMillis,
      int writeTimeoutMillis,
      Clock clock) {
    this.requestFactory = requestFactory;
    this.jsonFactory = jsonFactory;
    this.endpoint = new GenericUrl(FCM_ENDPOINT.formatted(projectId));
    this.connectTimeoutMillis = connectTimeoutMillis;
    this.readTimeoutMillis = readTimeoutMillis;
    this.writeTimeoutMillis = writeTimeoutMillis;
    this.clock = clock;
  }

  @Override
  public FirebaseCallResult send(Message message) {
    try {
      HttpRequest request =
          requestFactory.buildPostRequest(
              endpoint, new JsonHttpContent(jsonFactory, Map.of("message", message)));
      request
          .setNumberOfRetries(0)
          .setRetryOnExecuteIOException(false)
          .setFollowRedirects(false)
          .setThrowExceptionOnExecuteError(false)
          .setLoggingEnabled(false)
          .setCurlLoggingEnabled(false)
          .setConnectTimeout(connectTimeoutMillis)
          .setReadTimeout(readTimeoutMillis)
          .setWriteTimeout(writeTimeoutMillis);
      return readResponse(request.execute());
    } catch (IOException | RuntimeException exception) {
      return FirebaseCallResult.failed(classifyTransportFailure(exception));
    }
  }

  private FirebaseCallResult readResponse(HttpResponse response) {
    try {
      int status = response.getStatusCode();
      Map<String, Object> body = parseBody(response);
      if (status >= 200 && status <= 299) {
        Object name = body.get("name");
        if (name instanceof String providerMessageId && !providerMessageId.isBlank()) {
          return FirebaseCallResult.accepted(providerMessageId);
        }
        return FirebaseCallResult.failed(
            FirebaseCallFailure.providerResponse(status, "MALFORMED_SUCCESS", null));
      }
      return FirebaseCallResult.failed(
          FirebaseCallFailure.providerResponse(
              status, providerErrorCode(body), retryAfter(response.getHeaders(), clock)));
    } catch (IOException | RuntimeException exception) {
      return FirebaseCallResult.failed(classifyTransportFailure(exception));
    } finally {
      try {
        response.disconnect();
      } catch (IOException ignored) {
        // Response evidence has already been reduced to the closed internal outcome.
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parseBody(HttpResponse response) throws IOException {
    if (response.getContent() == null) return Map.of();
    Object parsed = jsonFactory.fromInputStream(response.getContent(), Map.class);
    return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  private static String providerErrorCode(Map<String, Object> body) {
    Object errorObject = body.get("error");
    if (!(errorObject instanceof Map<?, ?> error)) return "UNKNOWN";
    Object detailsObject = error.get("details");
    if (detailsObject instanceof List<?> details) {
      for (Object detailObject : details) {
        if (detailObject instanceof Map<?, ?> detail
            && detail.get("errorCode") instanceof String errorCode
            && !errorCode.isBlank()) {
          return errorCode;
        }
      }
    }
    Object status = error.get("status");
    return status instanceof String value && !value.isBlank() ? value : "UNKNOWN";
  }

  static FirebaseCallFailure classifyTransportFailure(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof UnknownHostException
          || current instanceof ConnectException
          || current instanceof SSLHandshakeException) {
        return FirebaseCallFailure.provenPreConnect();
      }
    }
    return FirebaseCallFailure.postWriteAmbiguous("HTTP_TRANSPORT_FAILURE");
  }

  static Duration retryAfter(Map<String, ?> headers, Clock clock) {
    for (Map.Entry<String, ?> header : headers.entrySet()) {
      if (!"retry-after".equalsIgnoreCase(header.getKey())) continue;
      String value = singleHeaderValue(header.getValue());
      if (value == null) return null;
      try {
        long seconds = Long.parseLong(value);
        return seconds > 0 ? Duration.ofSeconds(seconds) : null;
      } catch (NumberFormatException ignored) {
        return retryAfterDate(value, clock);
      }
    }
    return null;
  }

  private static String singleHeaderValue(Object raw) {
    if (raw instanceof String value) return value.strip();
    if (raw instanceof List<?> values
        && values.size() == 1
        && values.getFirst() instanceof String value) {
      return value.strip();
    }
    return null;
  }

  private static Duration retryAfterDate(String value, Clock clock) {
    try {
      Instant retryAt =
          ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      Duration duration = Duration.between(clock.instant(), retryAt);
      return duration.isPositive() ? duration : null;
    } catch (DateTimeException | ArithmeticException exception) {
      return null;
    }
  }
}
