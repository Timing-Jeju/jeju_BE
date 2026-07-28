package com.timingjeju.api.global.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

public final class SecurityAuthenticationFailureHandler implements AuthenticationFailureHandler {

  private final AuthenticationEntryPoint authenticationEntryPoint;
  private final SecurityErrorResponseWriter responseWriter;

  public SecurityAuthenticationFailureHandler(
      AuthenticationEntryPoint authenticationEntryPoint,
      SecurityErrorResponseWriter responseWriter) {
    this.authenticationEntryPoint = authenticationEntryPoint;
    this.responseWriter = responseWriter;
  }

  @Override
  public void onAuthenticationFailure(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException, ServletException {
    if (!(exception instanceof AuthenticationServiceException)) {
      authenticationEntryPoint.commence(request, response, exception);
      return;
    }
    if (RemoteJwksFailureClassifier.isAvailabilityFailure(exception)) {
      authenticationEntryPoint.commence(request, response, exception);
      return;
    }
    responseWriter.write(
        response,
        HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
        "AUTH_INTERNAL_ERROR",
        "인증 처리 중 내부 오류가 발생했습니다.");
  }
}
