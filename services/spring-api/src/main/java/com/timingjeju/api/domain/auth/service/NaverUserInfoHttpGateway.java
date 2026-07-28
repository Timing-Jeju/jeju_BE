package com.timingjeju.api.domain.auth.service;

import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import com.timingjeju.api.domain.auth.exception.NaverUserInfoFailureCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.ObjectMapper;

public final class NaverUserInfoHttpGateway implements NaverUserInfoGateway {

  private static final URI PRODUCTION_USER_INFO_URI =
      URI.create("https://openapi.naver.com/v1/nid/me");
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
  private static final int MAX_RESPONSE_BYTES = 64 * 1024;

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final URI userInfoUri;

  private NaverUserInfoHttpGateway(
      HttpClient httpClient, ObjectMapper objectMapper, URI userInfoUri) {
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.userInfoUri = userInfoUri;
  }

  public static NaverUserInfoHttpGateway production(ObjectMapper objectMapper) {
    HttpClient securedClient =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    return new NaverUserInfoHttpGateway(securedClient, objectMapper, PRODUCTION_USER_INFO_URI);
  }

  static NaverUserInfoHttpGateway forTest(
      HttpClient httpClient, ObjectMapper objectMapper, URI userInfoUri) {
    return new NaverUserInfoHttpGateway(httpClient, objectMapper, userInfoUri);
  }

  @Override
  public Map<String, Object> getUserInfo(String providerAccessToken) {
    HttpRequest request =
        HttpRequest.newBuilder(userInfoUri)
            .GET()
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + providerAccessToken)
            .build();
    AtomicReference<BoundedBodyGateway> bodySubscriber = new AtomicReference<>();
    CompletableFuture<HttpResponse<byte[]>> responseFuture =
        httpClient.sendAsync(
            request,
            responseInfo -> {
              if (responseInfo.statusCode() != 200) {
                return HttpResponse.BodySubscribers.replacing(new byte[0]);
              }
              BoundedBodyGateway subscriber = new BoundedBodyGateway(MAX_RESPONSE_BYTES);
              bodySubscriber.set(subscriber);
              return subscriber;
            });
    try {
      HttpResponse<byte[]> response =
          responseFuture.get(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      return handleResponse(response.statusCode(), response.body());
    } catch (TimeoutException exception) {
      cancel(responseFuture, bodySubscriber.get());
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_TIMEOUT);
    } catch (ExecutionException exception) {
      throw mapAsyncFailure(exception.getCause());
    } catch (InterruptedException exception) {
      cancel(responseFuture, bodySubscriber.get());
      Thread.currentThread().interrupt();
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_TIMEOUT);
    } catch (RuntimeException exception) {
      if (exception instanceof NaverUserInfoException naverFailure) {
        throw naverFailure;
      }
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_UNAVAILABLE);
    }
  }

  private static void cancel(
      CompletableFuture<HttpResponse<byte[]>> responseFuture, BoundedBodyGateway bodySubscriber) {
    if (bodySubscriber != null) {
      bodySubscriber.cancel();
    }
    responseFuture.cancel(true);
  }

  private static NaverUserInfoException mapAsyncFailure(Throwable failure) {
    Throwable cause = failure;
    while (cause.getCause() != null
        && (cause instanceof CompletionException || cause instanceof ExecutionException)) {
      cause = cause.getCause();
    }
    if (cause instanceof NaverUserInfoException naverFailure) {
      return naverFailure;
    }
    if (cause instanceof HttpTimeoutException) {
      return new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_TIMEOUT);
    }
    return new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_UNAVAILABLE);
  }

  private Map<String, Object> handleResponse(int status, byte[] body) {
    if (status == 200) {
      return parse(body);
    }
    if (status == 401) {
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_UNAUTHORIZED);
    }
    if (status == 403) {
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_FORBIDDEN);
    }
    if (status == 429) {
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_RATE_LIMITED);
    }
    if (status >= 500 && status <= 599) {
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_UNAVAILABLE);
    }
    throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_MALFORMED_RESPONSE);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parse(byte[] body) {
    try {
      Object decoded = objectMapper.readValue(body, Map.class);
      if (!(decoded instanceof Map<?, ?> map)) {
        throw invalidResponse();
      }
      return (Map<String, Object>) map;
    } catch (RuntimeException exception) {
      throw invalidResponse();
    }
  }

  private static NaverUserInfoException invalidResponse() {
    return new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_MALFORMED_RESPONSE);
  }

  private static final class BoundedBodyGateway implements HttpResponse.BodySubscriber<byte[]> {

    private final int maxBytes;
    private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;

    private BoundedBodyGateway(int maxBytes) {
      this.maxBytes = maxBytes;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public synchronized void onSubscribe(Flow.Subscription subscription) {
      if (this.subscription != null) {
        subscription.cancel();
        return;
      }
      this.subscription = subscription;
      subscription.request(1);
    }

    @Override
    public synchronized void onNext(List<ByteBuffer> buffers) {
      if (body.isDone()) {
        return;
      }
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > maxBytes - output.size()) {
          subscription.cancel();
          body.completeExceptionally(
              new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_RESPONSE_TOO_LARGE));
          return;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        output.writeBytes(bytes);
      }
      subscription.request(1);
    }

    @Override
    public synchronized void onError(Throwable throwable) {
      body.completeExceptionally(throwable);
    }

    @Override
    public synchronized void onComplete() {
      body.complete(output.toByteArray());
    }

    private synchronized void cancel() {
      if (subscription != null) {
        subscription.cancel();
      }
      body.cancel(true);
    }
  }
}
