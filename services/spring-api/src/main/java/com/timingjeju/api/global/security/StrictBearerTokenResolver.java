package com.timingjeju.api.global.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

public final class StrictBearerTokenResolver implements BearerTokenResolver {

  private final DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();

  @Override
  public String resolve(HttpServletRequest request) {
    if (Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).size() > 1) {
      throw new org.springframework.security.oauth2.core.OAuth2AuthenticationException(
          "Authorization 헤더는 하나만 허용됩니다.");
    }
    return delegate.resolve(request);
  }
}
