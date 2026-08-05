package com.timingjeju.api.global.security;

import com.timingjeju.api.global.error.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.DefaultCorsProcessor;

public final class ProblemCorsProcessor implements CorsProcessor {

  private static final String ACCESS_DENIED_CODE = "AUTH_ACCESS_DENIED";

  private final CorsProcessor delegate =
      new DefaultCorsProcessor() {
        @Override
        protected void rejectRequest(ServerHttpResponse response) {
          // The shared writer owns the only public error body.
        }
      };
  private final ProblemResponseWriter responseWriter;

  public ProblemCorsProcessor(ProblemResponseWriter responseWriter) {
    this.responseWriter = responseWriter;
  }

  @Override
  public boolean processRequest(
      CorsConfiguration configuration, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    boolean accepted = delegate.processRequest(configuration, request, response);
    if (!accepted) {
      responseWriter.write(request, response, ACCESS_DENIED_CODE);
    }
    return accepted;
  }
}
