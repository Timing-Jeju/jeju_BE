package com.timingjeju.api.global.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public final class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final SecurityErrorResponseWriter responseWriter;

  public JsonAuthenticationEntryPoint(SecurityErrorResponseWriter responseWriter) {
    this.responseWriter = responseWriter;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authenticationException)
      throws IOException, ServletException {
    responseWriter.write(
        response, HttpServletResponse.SC_UNAUTHORIZED, "AUTH_TOKEN_INVALID", "인증 토큰이 유효하지 않습니다.");
  }
}
