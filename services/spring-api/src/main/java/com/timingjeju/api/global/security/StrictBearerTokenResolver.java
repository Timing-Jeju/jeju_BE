package com.timingjeju.api.global.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

public final class StrictBearerTokenResolver implements BearerTokenResolver {

  private final DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();

  @Override
  public String resolve(HttpServletRequest request) {
    List<String> authorizationHeaders =
        Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
    if (authorizationHeaders.isEmpty()) {
      return null;
    }
    if (authorizationHeaders.size() > 1) {
      throw invalidAccessToken();
    }
    String token = delegate.resolve(request);
    if (token == null) {
      throw invalidAccessToken();
    }
    return token;
  }

  private static OAuth2AuthenticationException invalidAccessToken() {
    return new OAuth2AuthenticationException("invalid_token");
  }
}
