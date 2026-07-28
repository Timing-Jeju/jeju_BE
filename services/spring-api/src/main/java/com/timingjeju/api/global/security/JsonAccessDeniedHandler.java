package com.timingjeju.api.global.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

public final class JsonAccessDeniedHandler implements AccessDeniedHandler {

  private final SecurityErrorResponseWriter responseWriter;

  public JsonAccessDeniedHandler(SecurityErrorResponseWriter responseWriter) {
    this.responseWriter = responseWriter;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException, ServletException {
    responseWriter.write(
        response, HttpServletResponse.SC_FORBIDDEN, "AUTH_ACCESS_DENIED", "접근 권한이 없습니다.");
  }
}
