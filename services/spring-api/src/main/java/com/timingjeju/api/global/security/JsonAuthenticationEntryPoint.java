package com.timingjeju.api.global.security;

import com.timingjeju.api.global.error.AuthenticationProblemWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

public final class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final AuthenticationProblemWriter problemWriter;

  public JsonAuthenticationEntryPoint(AuthenticationProblemWriter problemWriter) {
    this.problemWriter = problemWriter;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authenticationException)
      throws IOException, ServletException {
    problemWriter.writeCanonical(request, response);
  }
}
