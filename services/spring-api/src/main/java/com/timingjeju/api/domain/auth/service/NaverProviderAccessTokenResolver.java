package com.timingjeju.api.domain.auth.service;

import com.timingjeju.api.domain.auth.exception.NaverUserInfoException;
import com.timingjeju.api.domain.auth.exception.NaverUserInfoFailureCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.http.HttpHeaders;

public final class NaverProviderAccessTokenResolver {

  private static final int MAX_TOKEN_LENGTH = 4096;

  public String resolve(HttpServletRequest request) {
    if (request.getQueryString() != null || isFormUrlEncoded(request.getContentType())) {
      throw invalidToken();
    }
    var headers = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
    if (headers.size() != 1) {
      throw invalidToken();
    }
    String value = headers.getFirst();
    if (value.length() <= "Bearer ".length()
        || !value.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
      throw invalidToken();
    }
    String token = value.substring("Bearer ".length());
    if (token.isBlank()
        || token.length() > MAX_TOKEN_LENGTH
        || token.chars().anyMatch(Character::isWhitespace)) {
      throw invalidToken();
    }
    return token;
  }

  private static NaverUserInfoException invalidToken() {
    return new NaverUserInfoException(NaverUserInfoFailureCode.PROVIDER_TOKEN_INVALID);
  }

  private static boolean isFormUrlEncoded(String contentType) {
    return contentType != null
        && contentType.regionMatches(
            true,
            0,
            "application/x-www-form-urlencoded",
            0,
            "application/x-www-form-urlencoded".length());
  }
}
