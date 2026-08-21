package com.timingjeju.api.global.security;

import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public final class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ProblemResponseWriter responseWriter;

  public JsonAuthenticationEntryPoint(ProblemResponseWriter responseWriter) {
    this.responseWriter = responseWriter;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authenticationException)
      throws IOException, ServletException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean optionalPlacesGet = "GET".equals(request.getMethod()) && isOptionalPlacesPath(path);
    responseWriter.write(
        request, response, optionalPlacesGet ? "INVALID_ACCESS_TOKEN" : "AUTH_TOKEN_INVALID");
  }

  private static boolean isOptionalPlacesPath(String path) {
    if ("/api/v1/places".equals(path)) {
      return true;
    }
    String prefix = "/api/v1/places/";
    return path.startsWith(prefix)
        && path.length() > prefix.length()
        && path.indexOf('/', prefix.length()) < 0;
  }
}
