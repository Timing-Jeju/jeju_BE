package com.timingjeju.api.domain.auth.service;

import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import com.timingjeju.api.domain.auth.exception.NaverUserInfoFailureCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
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
    try {
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        return handleResponse(response.statusCode(), body);
      }
    } catch (HttpTimeoutException exception) {
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_TIMEOUT);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_TIMEOUT);
    } catch (IOException exception) {
      throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_UNAVAILABLE);
    }
  }

  private Map<String, Object> handleResponse(int status, InputStream body) throws IOException {
    if (status == 200) {
      return parse(readLimited(body));
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

  private byte[] readLimited(InputStream body) throws IOException {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int total = 0;
      for (int read; (read = body.read(buffer)) != -1; ) {
        total += read;
        if (total > MAX_RESPONSE_BYTES) {
          throw new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_RESPONSE_TOO_LARGE);
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  private static NaverUserInfoException invalidResponse() {
    return new NaverUserInfoException(NaverUserInfoFailureCode.UPSTREAM_MALFORMED_RESPONSE);
  }
}
