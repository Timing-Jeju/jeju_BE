package com.timingjeju.api.global.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;

public final class AuthenticationProblemWriter {

  private final ProblemResponseWriter responseWriter;

  public AuthenticationProblemWriter(ProblemResponseWriter responseWriter) {
    this.responseWriter = responseWriter;
  }

  public boolean writeCanonical(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String code =
        request.getHeaders(HttpHeaders.AUTHORIZATION).hasMoreElements()
            ? StandardProblemCode.INVALID_ACCESS_TOKEN.name()
            : StandardProblemCode.AUTHENTICATION_REQUIRED.name();
    return write(request, response, code);
  }

  public boolean writeLegacy(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    return write(request, response, StandardProblemCode.AUTH_TOKEN_INVALID.name());
  }

  private boolean write(HttpServletRequest request, HttpServletResponse response, String code)
      throws IOException {
    if (response.isCommitted()) {
      return false;
    }
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    return responseWriter.write(request, response, code);
  }
}
