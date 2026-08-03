package com.timingjeju.api.global.security;

import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

public final class JsonAccessDeniedHandler implements AccessDeniedHandler {

  private final ProblemResponseWriter responseWriter;

  public JsonAccessDeniedHandler(ProblemResponseWriter responseWriter) {
    this.responseWriter = responseWriter;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException, ServletException {
    responseWriter.write(request, response, "AUTH_ACCESS_DENIED");
  }
}
